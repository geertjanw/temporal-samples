package com.example.stuart.workflow;

import com.example.stuart.workflow.Model.DevTask;
import com.example.stuart.workflow.Model.MergeRequest;
import com.example.stuart.workflow.Model.PhaseRequest;
import com.example.stuart.workflow.Model.PhaseResult;
import io.temporal.activity.ActivityInterface;

/** Every side effect (git, LLM calls, VCS) lives behind an activity boundary with retries. */
@ActivityInterface
public interface DevActivities {

    /** Fresh, isolated clone on a task branch. Returns the workspace path. */
    String prepareWorkspace(String workflowId, DevTask task);

    /** One agent run (heartbeats every iteration). */
    PhaseResult runPhase(PhaseRequest request);

    /** Commit everything, optionally push, and "open" the merge request. */
    MergeRequest openMergeRequest(String workspacePath, DevTask task, String description);

    /** Commit follow-up changes from review. Returns the new commit sha, or null if nothing changed. */
    String commitReviewChanges(String workspacePath, DevTask task, String message);
}
