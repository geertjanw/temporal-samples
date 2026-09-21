package petclinic.reminder;

import java.time.LocalDate;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;

/**
 * Starts an appointment-reminder Workflow — the standalone stand-in for PetClinic's
 * "book a visit" web request. Run it while a {@link ReminderWorker} is running.
 *
 * <p>
 * Usage: {@code BookVisit [visitDate yyyy-MM-dd] [description]}. With no arguments it books
 * a visit for tomorrow, which makes the reminder due immediately so you see it fire in
 * seconds. Pass a date further out to watch the durable timer wait.
 */
public class BookVisit {

	public static void main(String[] args) {
		String visitDate = (args.length > 0) ? args[0] : LocalDate.now().plusDays(1).toString();
		String description = (args.length > 1) ? args[1] : "Annual checkup and vaccinations";

		// Sample booking — in PetClinic this comes from the VisitController form.
		ReminderInput input = new ReminderInput("George Franklin", "Leo", "6085551023", visitDate, description);

		// Deterministic Workflow ID keyed by pet + date: booking the same visit twice does
		// not create a duplicate reminder.
		String workflowId = "reminder-" + input.getPetName().toLowerCase() + "-" + visitDate;

		WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
		WorkflowClient client = WorkflowClient.newInstance(service);

		AppointmentReminderWorkflow workflow = client.newWorkflowStub(AppointmentReminderWorkflow.class,
				WorkflowOptions.newBuilder()
					.setTaskQueue(ReminderConstants.TASK_QUEUE)
					.setWorkflowId(workflowId)
					.build());

		try {
			// Non-blocking start: returns as soon as the Workflow is created on the server.
			WorkflowClient.start(workflow::scheduleReminder, input);
			System.out.println("Booked visit for " + input.getPetName() + " on " + visitDate);
			System.out.println("Started reminder workflow '" + workflowId + "'");
		}
		catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
			System.out.println("Reminder workflow '" + workflowId + "' already exists — skipping duplicate");
		}

		System.out.println("Check status: temporal workflow query --workflow-id " + workflowId + " --type status");
		System.out.println("Or open the Web UI: http://localhost:8233");
		System.exit(0);
	}

}
