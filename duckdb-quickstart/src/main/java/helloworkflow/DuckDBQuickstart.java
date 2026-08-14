// 1. Create the Java source file
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

public class DuckDBQuickstart {

    static final String TASK_QUEUE = "duckdb-quickstart";
    static final String WORKFLOW_ID = "duckdb-ingest";

    // The embedded DuckDB database lives in a single file next to the process.
    static final String JDBC_URL = "jdbc:duckdb:duckdb-quickstart.duckdb";

    // 2. Activity interface
    @ActivityInterface
    public interface IngestActivities {
        void createTable();

        double readTemperature();

        void store(int id, double temperature);

        String summarize();
    }

    // 3. Activity implementation
    public static class IngestActivitiesImpl implements IngestActivities {

        @Override
        public void createTable() {
            try (Connection conn = DriverManager.getConnection(JDBC_URL);
                 Statement stmt = conn.createStatement()) {
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS readings ("
                        + "id INTEGER PRIMARY KEY, temperature DOUBLE)");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public double readTemperature() {
            // Stand-in for real hardware or an HTTP call.
            return Math.round((15 + Math.random() * 10) * 10) / 10.0;
        }

        @Override
        public void store(int id, double temperature) {
            // INSERT OR IGNORE keeps the write idempotent: if the Activity is
            // retried after already committing, the duplicate id is a no-op.
            try (Connection conn = DriverManager.getConnection(JDBC_URL);
                 PreparedStatement stmt = conn.prepareStatement(
                     "INSERT OR IGNORE INTO readings VALUES (?, ?)")) {
                stmt.setInt(1, id);
                stmt.setDouble(2, temperature);
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String summarize() {
            try (Connection conn = DriverManager.getConnection(JDBC_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT count(*), min(temperature), max(temperature), avg(temperature) "
                         + "FROM readings")) {
                rs.next();
                return String.format(
                    "%d readings — min %.1f °C, max %.1f °C, avg %.1f °C",
                    rs.getInt(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // 4. Workflow interface
    @WorkflowInterface
    public interface IngestWorkflow {
        @WorkflowMethod
        String ingest(int readingCount);
    }

    // 5. Workflow implementation
    public static class IngestWorkflowImpl implements IngestWorkflow {

        private final IngestActivities activities =
            Workflow.newActivityStub(
                IngestActivities.class,
                ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(5))
                    .build());

        @Override
        public String ingest(int readingCount) {
            activities.createTable();

            for (int id = 1; id <= readingCount; id++) {
                double temperature = activities.readTemperature();
                activities.store(id, temperature);
                System.out.printf("Stored reading %d of %d: %.1f °C%n",
                    id, readingCount, temperature);
                Workflow.sleep(Duration.ofSeconds(10));
            }

            // A single analytical query over everything we ingested.
            return activities.summarize();
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
        worker.registerWorkflowImplementationTypes(IngestWorkflowImpl.class);
        worker.registerActivitiesImplementations(new IngestActivitiesImpl());
        factory.start();

        IngestWorkflow workflow =
            client.newWorkflowStub(
                IngestWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(TASK_QUEUE)
                    .setWorkflowId(WORKFLOW_ID)
                    .build());

        String summary;
        try {
            summary = workflow.ingest(10);
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
