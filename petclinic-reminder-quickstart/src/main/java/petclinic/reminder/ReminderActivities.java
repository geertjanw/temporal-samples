package petclinic.reminder;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Side-effecting work for the reminder. Activities are where Temporal allows
 * non-deterministic operations (sending an SMS/email, DB access, HTTP calls); they are
 * retried automatically on failure. The Workflow itself stays deterministic and delegates
 * all such work here.
 */
@ActivityInterface
public interface ReminderActivities {

	/**
	 * Deliver the reminder to the pet owner. In this sample it logs the message that would
	 * otherwise be sent via an SMS/email provider.
	 * @param input the appointment details
	 * @return a human-readable summary of what was sent (surfaced in Workflow history)
	 */
	@ActivityMethod
	String sendReminder(ReminderInput input);

}
