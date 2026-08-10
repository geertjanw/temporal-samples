package helloworkflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SensorQuickstart {

    static final String TASK_QUEUE = "sensor-quickstart";
    static final String WORKFLOW_ID = "sensor-monitor";

    // 2. Activity interface
    @ActivityInterface
    public interface SensorActivities {
        double readTemperature();
    }

    // 3. Activity implementation
    public static class SensorActivitiesImpl implements SensorActivities {
        @Override
        public double readTemperature() {
            // Stand-in for real hardware or an HTTP call.
            return Math.round((15 + Math.random() * 10) * 10) / 10.0;
        }
    }

    // 4. Workflow interface
    @WorkflowInterface
    public interface SensorWorkflow {
        @WorkflowMethod
        String monitor(int readingCount);
    }

    // 5. Workflow implementation
    public static class SensorWorkflowImpl implements SensorWorkflow {

        private final SensorActivities sensor =
            Workflow.newActivityStub(
                SensorActivities.class,
                ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(5))
                    .build());

        @Override
        public String monitor(int readingCount) {
            List<Double> readings = new ArrayList<>();
            for (int i = 1; i <= readingCount; i++) {
                double temperature = sensor.readTemperature();
                readings.add(temperature);
                System.out.printf("Reading %d of %d: %.1f °C%n", i, readingCount, temperature);
                Workflow.sleep(Duration.ofSeconds(10));
            }

            double min = Collections.min(readings);
            double max = Collections.max(readings);
            double avg = readings.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
            return String.format("%d readings — min %.1f °C, max %.1f °C, avg %.1f °C",
                readingCount, min, max, avg);
        }
    }

    // 6. + 7. Worker startup and Workflow execution
    public static void main(String[] args) {

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();

        WorkflowClient client = WorkflowClient.newInstance(service);

        // A Worker polls a Task Queue for work and executes it. This is the
        // process you are going to kill and restart.
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(SensorWorkflowImpl.class);
        worker.registerActivitiesImplementations(new SensorActivitiesImpl());
        factory.start();

        SensorWorkflow workflow =
            client.newWorkflowStub(
                SensorWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(TASK_QUEUE)
                    .setWorkflowId(WORKFLOW_ID)
                    .build());

        String summary;
        try {
            summary = workflow.monitor(10);
        } catch (WorkflowExecutionAlreadyStarted e) {
            // We were killed and restarted: the workflow is still running.
            // Attach and wait for the result instead of starting a new one.
            System.out.println("Resuming existing workflow after crash...");
            summary = client.newUntypedWorkflowStub(WORKFLOW_ID).getResult(String.class);
        }

        System.out.println("Summary: " + summary);
        System.exit(0);
    }

}
