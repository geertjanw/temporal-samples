package petclinic.reminder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;

/**
 * Bridges an <em>unmodified</em> Spring PetClinic to Temporal by reading its database.
 *
 * <p>
 * PetClinic is a server-rendered MVC app with no write API and no Temporal code — so the
 * only clean way to react to a booked visit, without changing PetClinic, is to observe the
 * data it persists. This process polls PetClinic's {@code visits} table (joined to
 * {@code pets} and {@code owners}) and starts one {@link AppointmentReminderWorkflow} per
 * visit.
 *
 * <p>
 * Exactly-once per visit is enforced by a deterministic Workflow ID
 * ({@code reminder-visit-<id>}) plus a REJECT_DUPLICATE reuse policy: re-seeing the same
 * visit on the next poll is a no-op, whether its reminder is still running or already sent.
 *
 * <p>
 * Requires PetClinic to run against a shared database (e.g. the Postgres it ships with),
 * not the default embedded in-memory H2. Connection defaults to PetClinic's Postgres and
 * can be overridden with {@code POSTGRES_URL}, {@code POSTGRES_USER}, {@code POSTGRES_PASS}.
 */
public class PetClinicBridge {

	private static final String JDBC_URL = env("POSTGRES_URL", "jdbc:postgresql://localhost/petclinic");

	private static final String JDBC_USER = env("POSTGRES_USER", "petclinic");

	private static final String JDBC_PASS = env("POSTGRES_PASS", "petclinic");

	private static final long POLL_INTERVAL_MS = 5_000;

	private static final String VISITS_QUERY = """
			SELECT v.id            AS visit_id,
			       o.first_name    AS first_name,
			       o.last_name     AS last_name,
			       p.name          AS pet_name,
			       o.telephone     AS telephone,
			       v.visit_date    AS visit_date,
			       v.description   AS description
			  FROM visits v
			  JOIN pets   p ON v.pet_id   = p.id
			  JOIN owners o ON p.owner_id = o.id
			""";

	public static void main(String[] args) throws Exception {
		WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
		WorkflowClient client = WorkflowClient.newInstance(service);

		System.out.println("PetClinic bridge started. Polling " + JDBC_URL + " every " + (POLL_INTERVAL_MS / 1000)
				+ "s for visits; starting reminder workflows on task queue '" + ReminderConstants.TASK_QUEUE
				+ "'. Press CTRL+C to stop.");

		while (true) {
			try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS);
					Statement stmt = conn.createStatement();
					ResultSet rs = stmt.executeQuery(VISITS_QUERY)) {
				while (rs.next()) {
					startReminder(client, rs);
				}
			}
			catch (Exception e) {
				System.out.println("Bridge poll failed (will retry): " + e.getMessage());
			}
			Thread.sleep(POLL_INTERVAL_MS);
		}
	}

	private static void startReminder(WorkflowClient client, ResultSet rs) throws Exception {
		int visitId = rs.getInt("visit_id");
		String ownerName = rs.getString("first_name") + " " + rs.getString("last_name");
		String petName = rs.getString("pet_name");
		String telephone = rs.getString("telephone");
		String visitDate = rs.getString("visit_date"); // ISO yyyy-MM-dd from a DATE column
		String description = rs.getString("description");

		String workflowId = "reminder-visit-" + visitId;
		AppointmentReminderWorkflow workflow = client.newWorkflowStub(AppointmentReminderWorkflow.class,
				WorkflowOptions.newBuilder()
					.setTaskQueue(ReminderConstants.TASK_QUEUE)
					.setWorkflowId(workflowId)
					// Reject if this visit's reminder has ever been started before, so each
					// visit produces exactly one reminder across all polls.
					.setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
					.build());

		ReminderInput input = new ReminderInput(ownerName, petName, telephone, visitDate, description);
		try {
			WorkflowClient.start(workflow::scheduleReminder, input);
			System.out.println("Scheduled reminder for visit " + visitId + " (" + petName + " on " + visitDate + ")");
		}
		catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
			// Already scheduled on a previous poll — nothing to do.
		}
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return (value != null && !value.isBlank()) ? value : fallback;
	}

}
