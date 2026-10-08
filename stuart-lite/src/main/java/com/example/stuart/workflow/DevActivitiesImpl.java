package com.example.stuart.workflow;

import com.example.stuart.StuartProperties;
import com.example.stuart.agent.AgentLoopService;
import com.example.stuart.agent.AgentResult;
import com.example.stuart.agent.AgentRun;
import com.example.stuart.tools.ReadTools;
import com.example.stuart.tools.Workspace;
import com.example.stuart.tools.WriteTools;
import com.example.stuart.workflow.Model.DevTask;
import com.example.stuart.workflow.Model.MergeRequest;
import com.example.stuart.workflow.Model.PhaseRequest;
import com.example.stuart.workflow.Model.PhaseResult;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
@ActivityImpl(taskQueues = Model.TASK_QUEUE)
public class DevActivitiesImpl implements DevActivities {

    private static final Logger log = LoggerFactory.getLogger(DevActivitiesImpl.class);

    private final AgentLoopService agentLoop;
    private final PromptFactory prompts;
    private final StuartProperties props;

    public DevActivitiesImpl(AgentLoopService agentLoop, PromptFactory prompts, StuartProperties props) {
        this.agentLoop = agentLoop;
        this.prompts = prompts;
        this.props = props;
    }

    @Override
    public String prepareWorkspace(String workflowId, DevTask task) {
        Path dir = Path.of(props.workspaceRoot()).resolve(workflowId.replaceAll("[^A-Za-z0-9._-]", "_"));
        try {
            // idempotent: a retried activity starts again from a clean clone
            FileSystemUtils.deleteRecursively(dir);
            Files.createDirectories(dir.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("cannot prepare " + dir, e);
        }
        List<String> clone = new ArrayList<>(List.of("clone", "--depth", "50"));
        if (task.baseBranch() != null && !task.baseBranch().isBlank()) {
            clone.addAll(List.of("--branch", task.baseBranch()));
        }
        clone.addAll(List.of(task.repoUrl(), dir.toString()));
        Git.run(dir.getParent(), clone.toArray(String[]::new));
        Git.run(dir, "checkout", "-b", branchName(task));
        log.info("Workspace for {} ready at {}", task.key(), dir);
        return dir.toString();
    }

    @Override
    public PhaseResult runPhase(PhaseRequest req) {
        Workspace ws = new Workspace(Path.of(req.workspacePath()));
        List<Object> tools = new ArrayList<>();
        tools.add(new ReadTools(ws));
        if (req.phase().writes()) {
            tools.add(new WriteTools(ws)); // read-only phases never even see write tools
        }
        AgentRun run = new AgentRun(
                prompts.system(req.phase(), ws),
                prompts.user(req),
                tools,
                WriteTools.NAMES,
                req.phase().maxIterations(),
                props.tokenBudgetPerPhase(),
                2);

        ActivityExecutionContext ctx = Activity.getExecutionContext();
        log.info("Task {}: starting phase {}", req.task().key(), req.phase());
        AgentResult r = agentLoop.run(run, iteration -> ctx.heartbeat(iteration));

        return new PhaseResult(req.phase(), r.finalText() == null ? "" : r.finalText(), r.finishReason().name(),
                r.iterations(), r.toolCalls(), r.promptTokens(), r.completionTokens(),
                props.estimateCost(r.promptTokens(), r.completionTokens()));
    }

    @Override
    public MergeRequest openMergeRequest(String workspacePath, DevTask task, String description) {
        Path dir = Path.of(workspacePath);
        String branch = branchName(task);
        String sha = Git.commitAll(dir, task.key() + ": " + task.title() + "\n\nImplemented by Stuart (AI agent).");
        if (sha == null) {
            sha = Git.run(dir, "rev-parse", "HEAD");
        }
        String diffStat = Git.tryRun(dir, "diff", "--stat", "HEAD~1", "HEAD").output().strip();
        if (props.gitPush()) {
            Git.run(dir, "push", "-u", "origin", branch);
        }
        // Integration point: call the GitLab / GitHub API here to open a real MR/PR with `description`,
        // and post the link to the team's Slack channel.
        log.info("Merge request for {} on branch {} ({}):\n{}\n{}", task.key(), branch, sha, diffStat, description);
        return new MergeRequest(branch, sha, diffStat);
    }

    @Override
    public String commitReviewChanges(String workspacePath, DevTask task, String message) {
        Path dir = Path.of(workspacePath);
        String sha = Git.commitAll(dir, task.key() + ": " + message);
        if (sha != null && props.gitPush()) {
            Git.run(dir, "push", "origin", branchName(task));
        }
        return sha;
    }

    private static String branchName(DevTask task) {
        return "stuart/" + task.key().toLowerCase().replaceAll("[^a-z0-9._-]", "-");
    }
}
