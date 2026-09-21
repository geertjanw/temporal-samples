package petclinic.reminder;

/**
 * Payload passed into the reminder Workflow. A plain mutable JavaBean so Temporal's default
 * (Jackson) data converter can serialize it without extra modules. {@code visitDate} is an
 * ISO-8601 date string (yyyy-MM-dd) so no {@code java.time} converter configuration is
 * required.
 */
public class ReminderInput {

	private String ownerName;

	private String petName;

	private String telephone;

	private String visitDate;

	private String description;

	public ReminderInput() {
	}

	public ReminderInput(String ownerName, String petName, String telephone, String visitDate, String description) {
		this.ownerName = ownerName;
		this.petName = petName;
		this.telephone = telephone;
		this.visitDate = visitDate;
		this.description = description;
	}

	public String getOwnerName() {
		return ownerName;
	}

	public void setOwnerName(String ownerName) {
		this.ownerName = ownerName;
	}

	public String getPetName() {
		return petName;
	}

	public void setPetName(String petName) {
		this.petName = petName;
	}

	public String getTelephone() {
		return telephone;
	}

	public void setTelephone(String telephone) {
		this.telephone = telephone;
	}

	public String getVisitDate() {
		return visitDate;
	}

	public void setVisitDate(String visitDate) {
		this.visitDate = visitDate;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

}
