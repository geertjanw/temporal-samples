package petclinic.reminder;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

/**
 * Implementation of {@link AppointmentReminderWorkflow}.
 *
 * <p>
 * Workflow code must be deterministic, so it uses the {@code Workflow.*} APIs
 * ({@code Workflow.currentTimeMillis()}, {@code Workflow.sleep()}) instead of their
 * standard-library equivalents, and pushes all I/O into the Activity.
 */
public class AppointmentReminderWorkflowImpl implements AppointmentReminderWorkflow {

	/** How many days before the visit the reminder should go out. */
	private static final long REMINDER_LEAD_DAYS = 1;

	/** Hour of day (UTC) to send the reminder on the reminder day. */
	private static final int REMINDER_HOUR_UTC = 9;

	private final ReminderActivities activities = Workflow.newActivityStub(ReminderActivities.class,
			ActivityOptions.newBuilder()
				.setTaskQueue(ReminderConstants.TASK_QUEUE)
				.setStartToCloseTimeout(Duration.ofSeconds(30))
				.setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
				.build());

	private String status = "WAITING";

	@Override
	public void scheduleReminder(ReminderInput input) {
		long nowMillis = Workflow.currentTimeMillis();
		long dueMillis = reminderDueEpochMillis(input.getVisitDate());
		Duration wait = Duration.ofMillis(Math.max(0, dueMillis - nowMillis));

		// Guard on isReplaying() so this line prints once (on first execution), not again
		// each time Temporal replays the Workflow's history after a restart.
		if (!Workflow.isReplaying()) {
			System.out.println("Reminder scheduled for " + input.getOwnerName() + "'s pet " + input.getPetName()
					+ " — waiting " + wait + " until it is due");
		}

		// Durable timer: persisted by Temporal, survives Worker/process restarts.
		Workflow.sleep(wait);

		activities.sendReminder(input);
		this.status = "SENT";
	}

	@Override
	public String status() {
		return this.status;
	}

	/**
	 * Compute when the reminder is due, in epoch millis. {@code LocalDate.parse} and the
	 * date arithmetic below are pure functions (no clock, no I/O), so they are safe to run
	 * inside deterministic Workflow code.
	 */
	private static long reminderDueEpochMillis(String visitDateIso) {
		LocalDate reminderDay = LocalDate.parse(visitDateIso).minusDays(REMINDER_LEAD_DAYS);
		return reminderDay.atTime(REMINDER_HOUR_UTC, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
	}

}
