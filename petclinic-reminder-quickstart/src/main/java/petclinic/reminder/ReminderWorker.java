package petclinic.reminder;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

/**
 * Long-running Worker process. It connects to the Temporal Service, registers the Workflow
 * and Activity implementations, and polls the Task Queue. In production this stays up
 * independently of whatever starts Workflows.
 *
 * <p>
 * Connects to {@code 127.0.0.1:7233} (the default {@code temporal server start-dev}
 * address) unless {@code TEMPORAL_ADDRESS} is set in the environment.
 */
public class ReminderWorker {

	public static void main(String[] args) {
		WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
		WorkflowClient client = WorkflowClient.newInstance(service);

		WorkerFactory factory = WorkerFactory.newInstance(client);
		Worker worker = factory.newWorker(ReminderConstants.TASK_QUEUE);
		worker.registerWorkflowImplementationTypes(AppointmentReminderWorkflowImpl.class);
		worker.registerActivitiesImplementations(new ReminderActivitiesImpl());

		System.out.println("Reminder worker started, polling task queue '" + ReminderConstants.TASK_QUEUE
				+ "'. Press CTRL+C to stop.");
		factory.start();
	}

}
