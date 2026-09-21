package petclinic.reminder;

/**
 * Reminder delivery implementation. Activity implementations must be stateless — Temporal
 * may invoke them concurrently across many Workflow executions. In a real app this would
 * hold an injected SMS/email gateway instead of printing.
 *
 * <p>
 * Activities run exactly once per successful attempt (they are not replayed like Workflow
 * code), so plain {@code System.out} here is safe and always visible.
 */
public class ReminderActivitiesImpl implements ReminderActivities {

	@Override
	public String sendReminder(ReminderInput input) {
		String message = "Reminder for %s: %s has a vet appointment on %s (%s)".formatted(input.getOwnerName(),
				input.getPetName(), input.getVisitDate(), input.getDescription());
		// A real implementation would call an SMS/email provider here.
		System.out.println("Sending appointment reminder to " + input.getTelephone() + " -> " + message);
		return message;
	}

}
