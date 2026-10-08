package com.example.stuart.workflow;

import com.example.stuart.workflow.Model.DevTask;
import com.example.stuart.workflow.Model.MergeRequest;
import com.example.stuart.workflow.Model.PhaseRequest;
import com.example.stuart.workflow.Model.PhaseResult;
import com.example.stuart.workflow.Model.ReviewComment;
import com.example.stuart.workflow.Model.ReviewThread;
import com.example.stuart.workflow.Model.WorkflowStatus;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic orchestration only: no I/O, no clocks, no randomness here. Everything with side effects
 * is an activity, so Temporal can replay this code after a crash and resume exactly where it stopped.
 *
 * <pre>
 * prepare workspace
 *   -> PLAN  (read-only; may ask clarification questions and wait for answers)
 *   -> IMPLEMENT
 *   -> PLAN_TESTS -> WRITE_TESTS      (skipped with label "no-tests")
 *   -> open merge request (description includes the budget table)
 *   -> review loop: each comment signal -> ADDRESS_REVIEW -> commit -> reply ... until approve()
 * </pre>
 */
@WorkflowImpl(taskQueues = Model.TASK_QUEUE)
public class DevTaskWorkflowImpl implements DevTaskWorkflow {

    private static final Logger log = Workflow.getLogger(DevTaskWorkflowImpl.class);
    private static final String CLARIFICATION_MARKER = "CLARIFICATION NEEDED";
    private static final int MAX_CLARIFICATION_ROUNDS = 3;
    private static final Duration HUMAN_TIMEOUT = Duration.ofDays(7);

    /** Cheap, retry-friendly activities (git). */
    private final DevActivities git = Workflow.newActivityStub(DevActivities.class, ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build());

    /** Agent runs: long, heartbeating, and expensive - so only one retry. */
    private final DevActivities agent = Workflow.newActivityStub(DevActivities.class, ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(45))
            .setHeartbeatTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(2)
                    .setInitialInterval(Duration.ofSeconds(30))
                    .build())
            .build());

    // ---- workflow state (rebuilt deterministically on replay) ----
    private String state = "STARTED";
    private String plan;
    private String questions;
    private String branch;
    private String mrDescription;
    private final List<PhaseResult> budget = new ArrayList<>();
    private final List<ReviewThread> threads = new ArrayList<>();
    private final Deque<String> clarifications = new ArrayDeque<>();
    private final Deque<ReviewComment> pendingComments = new ArrayDeque<>();
    private boolean approved;

    @Override
    public String implement(DevTask task) {
        state = "PREPARING_WORKSPACE";
        String ws = git.prepareWorkspace(Workflow.getInfo().getWorkflowId(), task);

        // 1. Plan (or reuse a plan that was already tech-reviewed)
        plan = task.plan();
        if (plan == null || plan.isBlank()) {
            String context = null;
            for (int round = 0; ; round++) {
                state = "PLANNING";
                PhaseResult result = runPhase(ws, task, Phase.PLAN, null, context);
                if (!result.output().strip().startsWith(CLARIFICATION_MARKER)) {
                    plan = result.output();
                    questions = null;
                    break;
                }
                if (round >= MAX_CLARIFICATION_ROUNDS) {
                    state = "FAILED_UNCLEAR_REQUIREMENTS";
                    return "Gave up: requirements still unclear after " + round + " clarification rounds.";
                }
                questions = result.output();
                state = "WAITING_FOR_CLARIFICATION";
                if (!Workflow.await(HUMAN_TIMEOUT, () -> !clarifications.isEmpty())) {
                    state = "TIMED_OUT_WAITING_FOR_CLARIFICATION";
                    return "No answer to clarification questions within " + HUMAN_TIMEOUT.toDays() + " days.";
                }
                context = (context == null ? "" : context + "\n\n")
                        + "Your earlier questions:\n" + questions + "\n\nAnswers from the analyst:\n" + clarifications.poll();
            }
        }
        if (task.hasLabel("tech-review")) {
            state = "PLAN_READY";
            return "Tech review done. Plan:\n" + plan;
        }

        // 2. Implement, 3. tests
        state = "IMPLEMENTING";
        PhaseResult impl = runPhase(ws, task, Phase.IMPLEMENT, plan, null);
        if (!task.hasLabel("no-tests")) {
            state = "PLANNING_TESTS";
            PhaseResult testPlan = runPhase(ws, task, Phase.PLAN_TESTS, plan, "Changes made:\n" + impl.output());
            state = "WRITING_TESTS";
            runPhase(ws, task, Phase.WRITE_TESTS, plan, "Test plan:\n" + testPlan.output());
        }

        // 4. Merge request
        state = "OPENING_MERGE_REQUEST";
        mrDescription = describeMergeRequest(task, impl.output());
        MergeRequest mr = git.openMergeRequest(ws, task, mrDescription);
        branch = mr.branch();

        // 5. Code review loop, driven by signals
        state = "IN_REVIEW";
        while (true) {
            boolean woke = Workflow.await(HUMAN_TIMEOUT, () -> approved || !pendingComments.isEmpty());
            if (!woke) {
                state = "REVIEW_TIMED_OUT";
                return "No review activity for " + HUMAN_TIMEOUT.toDays() + " days on branch " + branch;
            }
            if (pendingComments.isEmpty()) {
                break; // approved and nothing left to address
            }
            ReviewComment comment = pendingComments.poll();
            state = "ADDRESSING_REVIEW";
            PhaseResult fix = runPhase(ws, task, Phase.ADDRESS_REVIEW, plan,
                    "Review comment (thread " + comment.threadId() + "):\n" + comment.body());
            String sha = git.commitReviewChanges(ws, task, "Address review: " + firstLine(comment.body()));
            threads.add(new ReviewThread(comment.threadId(), comment.body(), fix.output(), sha));
            mrDescription = describeMergeRequest(task, impl.output());
            state = "IN_REVIEW";
        }

        state = "APPROVED_AWAITING_HUMAN_MERGE";
        log.info("Task {} approved on branch {}", task.key(), branch);
        return "Approved. Branch " + branch + " is ready for a human to merge.";
    }

    private PhaseResult runPhase(String ws, DevTask task, Phase phase, String plan, String instruction) {
        PhaseResult result = agent.runPhase(new PhaseRequest(ws, task, phase, plan, instruction));
        budget.add(result);
        return result;
    }

    private String describeMergeRequest(DevTask task, String summary) {
        StringBuilder sb = new StringBuilder()
                .append("## ").append(task.key()).append(": ").append(task.title()).append("\n\n")
                .append(summary).append("\n\n")
                .append("### Budget\n\n")
                .append("| Phase | Iterations | Tool calls | Prompt tokens | Completion tokens | Est. cost | Finish |\n")
                .append("|---|---:|---:|---:|---:|---:|---|\n");
        long p = 0, c = 0;
        double cost = 0;
        int iterations = 0;
        for (PhaseResult r : budget) {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | $%.2f | %s |%n",
                    r.phase(), r.iterations(), r.toolCalls(), r.promptTokens(), r.completionTokens(),
                    r.estimatedCostUsd(), r.finishReason()));
            p += r.promptTokens();
            c += r.completionTokens();
            cost += r.estimatedCostUsd();
            iterations += r.iterations();
        }
        sb.append(String.format(Locale.ROOT, "| **Total** | %d | | %d | %d | $%.2f | |%n", iterations, p, c, cost));
        return sb.toString();
    }

    private static String firstLine(String s) {
        String line = s.strip().lines().findFirst().orElse("");
        return line.length() > 60 ? line.substring(0, 57) + "..." : line;
    }

    // ---- signals & queries ----

    @Override
    public void answerClarification(String answer) {
        clarifications.add(answer);
    }

    @Override
    public void reviewComment(ReviewComment comment) {
        pendingComments.add(comment);
    }

    @Override
    public void approve() {
        approved = true;
    }

    @Override
    public WorkflowStatus status() {
        return new WorkflowStatus(state, plan, questions, branch, mrDescription, List.copyOf(threads));
    }
}
