package com.example.stuart.workflow;

import com.example.stuart.workflow.Model.DevTask;
import com.example.stuart.workflow.Model.ReviewComment;
import com.example.stuart.workflow.Model.WorkflowStatus;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * "Assign a task, get a merge request to review."
 * Long-running and durable: survives restarts and deployments, and waits days for humans if needed.
 */
@WorkflowInterface
public interface DevTaskWorkflow {

    @WorkflowMethod
    String implement(DevTask task);

    /** Answer to the questions the agent asked during planning (tech-review loop). */
    @SignalMethod
    void answerClarification(String answer);

    /** A reviewer left a comment on the merge request. */
    @SignalMethod
    void reviewComment(ReviewComment comment);

    /** The reviewer approved; a human does the actual merge. */
    @SignalMethod
    void approve();

    @QueryMethod
    WorkflowStatus status();
}
