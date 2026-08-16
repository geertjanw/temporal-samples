// 1. Create the Java source file
package helloworkflow;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.opentracingshim.OpenTracingShim;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
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

public class Dash0Quickstart {

    static final String TASK_QUEUE = "dash0-quickstart";
    static final String WORKFLOW_ID = "dash0-ingest";

    // The embedded DuckDB database lives in a single file next to the process.
    static final String JDBC_URL = "jdbc:duckdb:dash0-quickstart.duckdb";

    // Used to create the custom DuckDB spans that nest inside the Activity spans.
    static final Tracer TRACER = GlobalOpenTelemetry.getTracer("dash0-quickstart");

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
            String sql = "INSERT OR IGNORE INTO readings VALUES (?, ?)";
            // A child span so the DuckDB write shows up under the Activity in Dash0.
            Span span = dbSpan("INSERT readings", sql);
            try (Scope scope = span.makeCurrent();
                 Connection conn = DriverManager.getConnection(JDBC_URL);
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, id);
                stmt.setDouble(2, temperature);
                stmt.executeUpdate();
            } catch (SQLException e) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR);
                throw new RuntimeException(e);
            } finally {
                span.end();
            }
        }

        @Override
        public String summarize() {
            String sql =
                "SELECT count(*), min(temperature), max(temperature), avg(temperature) "
                    + "FROM readings";
            Span span = dbSpan("SELECT summary", sql);
            try (Scope scope = span.makeCurrent();
                 Connection conn = DriverManager.getConnection(JDBC_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                rs.next();
                return String.format(
                    "%d readings — min %.1f °C, max %.1f °C, avg %.1f °C",
                    rs.getInt(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4));
            } catch (SQLException e) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR);
                throw new RuntimeException(e);
            } finally {
                span.end();
            }
        }

        // A CLIENT span tagged with OpenTelemetry database semantic conventions.
        private static Span dbSpan(String name, String sql) {
            return TRACER.spanBuilder(name)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.system", "duckdb")
                .setAttribute("db.statement", sql)
                .startSpan();
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

    // 6. + 7. OpenTelemetry setup, Worker startup, and Workflow execution
    public static void main(String[] args) {

        // Reads OTEL_* environment variables (endpoint, headers, service name) and
        // builds an SDK that exports over OTLP to Dash0. Registered as the global
        // instance so the Activity spans above can find it.
        OpenTelemetrySdk otel =
            AutoConfiguredOpenTelemetrySdk.builder()
                .setResultAsGlobal()
                .build()
                .getOpenTelemetrySdk();

        // Bridge OpenTelemetry to the OpenTracing API the Temporal interceptors use.
        OpenTracingOptions tracing =
            OpenTracingOptions.newBuilder()
                .setTracer(OpenTracingShim.createTracerShim(otel))
                .build();

        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();

        // The client interceptor starts a span when a Workflow is started.
        WorkflowClient client =
            WorkflowClient.newInstance(
                service,
                WorkflowClientOptions.newBuilder()
                    .setInterceptors(new OpenTracingClientInterceptor(tracing))
                    .build());

        // The worker interceptor creates the Workflow and Activity spans, linked to
        // the client span so the whole run is one trace in Dash0.
        WorkerFactory factory =
            WorkerFactory.newInstance(
                client,
                WorkerFactoryOptions.newBuilder()
                    .setWorkerInterceptors(new OpenTracingWorkerInterceptor(tracing))
                    .build());
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

        // Flush any spans still buffered by the batch exporter before we exit,
        // otherwise the last traces may never reach Dash0.
        otel.close();
        System.exit(0);
    }

}
