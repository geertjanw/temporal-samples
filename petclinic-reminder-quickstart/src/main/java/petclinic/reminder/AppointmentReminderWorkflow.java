package petclinic.reminder;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Durable appointment reminder. When a visit is booked the Workflow starts and then sleeps
 * — using a Temporal durable timer — until shortly before the visit date, at which point it
 * invokes an Activity to notify the owner.
 *
 * <p>
 * The wait survives process restarts, crashes, and redeploys: Temporal persists the timer
 * server-side, so no scheduler, cron, or "sweep the DB for due reminders" job is needed.
 */
@WorkflowInterface
public interface AppointmentReminderWorkflow {

	/**
	 * Wait until the reminder is due, then send it.
	 * @param input the appointment details
	 */
	@WorkflowMethod
	void scheduleReminder(ReminderInput input);

	/**
	 * Inspect the current state of a running reminder (e.g. from the Temporal UI or CLI)
	 * without affecting it.
	 * @return {@code WAITING} while the timer is pending, {@code SENT} once delivered
	 */
	@QueryMethod
	String status();

}
