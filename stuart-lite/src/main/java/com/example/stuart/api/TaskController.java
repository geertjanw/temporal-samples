package com.example.stuart.api;

import com.example.stuart.workflow.DevTaskWorkflow;
import com.example.stuart.workflow.Model;
import com.example.stuart.workflow.Model.DevTask;
import com.example.stuart.workflow.Model.ReviewComment;
import com.example.stuart.workflow.Model.WorkflowStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stand-in for the Jira / GitLab / Slack webhooks of the real thing:
 * assigning a ticket starts a workflow, review comments and approvals become signals.
 */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final WorkflowClient client;

    public TaskController(WorkflowClient client) {
        this.client = client;
    }

    @PostMapping
    public Map<String, String> assign(@RequestBody DevTask task) {
        DevTaskWorkflow wf = client.newWorkflowStub(DevTaskWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(Model.TASK_QUEUE)
                .setWorkflowId(workflowId(task.key()))
                .build());
        var execution = WorkflowClient.start(wf::implement, task);
        return Map.of("workflowId", execution.getWorkflowId(), "runId", execution.getRunId());
    }

    @GetMapping("/{key}")
    public WorkflowStatus status(@PathVariable String key) {
        return stub(key).status();
    }

    @PostMapping("/{key}/clarification")
    public void clarify(@PathVariable String key, @RequestBody Map<String, String> body) {
        stub(key).answerClarification(body.get("answer"));
    }

    @PostMapping("/{key}/comments")
    public void comment(@PathVariable String key, @RequestBody ReviewComment comment) {
        stub(key).reviewComment(comment);
    }

    @PostMapping("/{key}/approve")
    public void approve(@PathVariable String key) {
        stub(key).approve();
    }

    private DevTaskWorkflow stub(String key) {
        return client.newWorkflowStub(DevTaskWorkflow.class, workflowId(key));
    }

    private static String workflowId(String key) {
        return "stuart-" + key;
    }
}
