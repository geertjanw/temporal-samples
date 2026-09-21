package petclinic.reminder;

/**
 * Shared constants. The Task Queue name is how the Worker and the Workflow/Activity stubs
 * find each other — it must be identical on both sides.
 */
public final class ReminderConstants {

	private ReminderConstants() {
	}

	/** Task Queue that the Worker polls and that Workflow/Activity stubs target. */
	public static final String TASK_QUEUE = "petclinic-reminders";

}
