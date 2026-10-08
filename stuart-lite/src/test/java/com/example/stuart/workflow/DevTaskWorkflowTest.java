package com.example.stuart.workflow;

import com.example.stuart.workflow.Model.DevTask;
import com.example.stuart.workflow.Model.MergeRequest;
import com.example.stuart.workflow.Model.PhaseRequest;
import com.example.stuart.workflow.Model.PhaseResult;
import com.example.stuart.workflow.Model.ReviewComment;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real workflow against an in-memory Temporal server with fake activities (no LLM, no git). */
class DevTaskWorkflowTest {

    private TestWorkflowEnvironment env;
    private FakeActivities activities;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(Model.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DevTaskWorkflowImpl.class);
        activities = new FakeActivities();
        worker.registerActivitiesImplementations(activities);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void clarifiesImplementsSkipsTestsAndAddressesReview() throws Exception {
        activities.askClarificationOnce = true;
        DevTaskWorkflow wf = start(new DevTask("PROJ-1", "Add field", "Expose agreement date", "repo",
                null, List.of("no-tests"), null));

        awaitState(wf, "WAITING_FOR_CLARIFICATION");
        assertThat(wf.status().clarificationQuestions()).contains("Which table?");
        wf.answerClarification("agreements");

        awaitState(wf, "IN_REVIEW");
        assertThat(wf.status().mergeRequestDescription()).contains("| Total").contains("PLAN").contains("IMPLEMENT");

        wf.reviewComment(new ReviewComment("t1", "Please rename the field"));
        wf.approve();

        String result = WorkflowStub.fromTyped(wf).getResult(String.class);
        assertThat(result).startsWith("Approved");
        assertThat(activities.phases).containsExactly(Phase.PLAN, Phase.PLAN, Phase.IMPLEMENT, Phase.ADDRESS_REVIEW);
        assertThat(wf.status().threads()).singleElement()
                .satisfies(t -> assertThat(t.reply()).isEqualTo("Done: ADDRESS_REVIEW"));
    }

    @Test
    void techReviewLabelStopsAfterPlan() {
        DevTaskWorkflow wf = start(new DevTask("PROJ-2", "Spec check", "...", "repo",
                null, List.of("tech-review"), null));
        String result = WorkflowStub.fromTyped(wf).getResult(String.class);
        assertThat(result).startsWith("Tech review done");
        assertThat(activities.phases).containsExactly(Phase.PLAN);
    }

    private DevTaskWorkflow start(DevTask task) {
        DevTaskWorkflow wf = env.getWorkflowClient().newWorkflowStub(DevTaskWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(Model.TASK_QUEUE).setWorkflowId("stuart-" + task.key()).build());
        WorkflowClient.start(wf::implement, task);
        return wf;
    }

    private static void awaitState(DevTaskWorkflow wf, String state) throws InterruptedException {
        for (int i = 0; i < 100 && !state.equals(wf.status().state()); i++) {
            Thread.sleep(50);
        }
        assertThat(wf.status().state()).isEqualTo(state);
    }

    static class FakeActivities implements DevActivities {
        final List<Phase> phases = new CopyOnWriteArrayList<>();
        volatile boolean askClarificationOnce;

        @Override
        public String prepareWorkspace(String workflowId, DevTask task) {
            return "/tmp/fake";
        }

        @Override
        public PhaseResult runPhase(PhaseRequest request) {
            phases.add(request.phase());
            String output = "Done: " + request.phase();
            if (request.phase() == Phase.PLAN && askClarificationOnce) {
                askClarificationOnce = false;
                output = "CLARIFICATION NEEDED\n1. Which table?";
            }
            return new PhaseResult(request.phase(), output, "COMPLETED", 3, 5, 1000, 200, 0.01);
        }

        @Override
        public MergeRequest openMergeRequest(String workspacePath, DevTask task, String description) {
            return new MergeRequest("stuart/" + task.key().toLowerCase(), "abc123", "1 file changed");
        }

        @Override
        public String commitReviewChanges(String workspacePath, DevTask task, String message) {
            return "def456";
        }
    }
}
