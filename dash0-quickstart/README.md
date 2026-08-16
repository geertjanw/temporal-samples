# Observable Durable Ingest with Dash0 in Java

The DuckDB ingest scenario made observable: **Temporal** durable workflows instrumented with **OpenTelemetry**, exporting workflow, activity, and database traces via OTLP to **[Dash0](https://www.dash0.com)** — in a single file.

## Why this sample?

Temporal already records *what happened* in its Event History, but that history lives inside Temporal. In a real system you also want workflow executions to show up in the same observability platform as the rest of your services — correlated as traces, with the database calls nested inside the activities that made them.

This sample shows the complete wiring for that with the Temporal Java SDK:

- **One trace per workflow run.** Temporal's OpenTracing interceptors create spans when a client starts a Workflow, when the Workflow executes, and for every Activity — all linked into a single trace.
- **Your own spans nest naturally.** The activities add custom `CLIENT` spans for each DuckDB statement, tagged with OpenTelemetry database semantic conventions (`db.system`, `db.statement`), so the SQL shows up under the right Activity in Dash0.
- **Zero-config export.** The OpenTelemetry SDK is built with autoconfiguration, so the OTLP endpoint, auth headers, and service name come from standard `OTEL_*` environment variables — nothing Dash0-specific is hardcoded.
- **Crash recovery, traced.** The scenario is the same durable ingest as [`duckdb-quickstart`](../duckdb-quickstart/): kill the process mid-run, re-run, and it resumes — and you can follow that story in the traces too.

## How it works

A Workflow takes ten (simulated) temperature readings, ten seconds apart, stores each as a row in an embedded DuckDB table, then runs one SQL query for the minimum, maximum, and average. The entire program is in one Java source file, [`Dash0Quickstart.java`](src/main/java/helloworkflow/Dash0Quickstart.java). There are four parts:

- **`IngestWorkflow`** — a Workflow that, for each reading, calls the `readTemperature` Activity and then the `store` Activity, sleeps ten seconds, and finally calls `summarize`.
- **`IngestActivities`** — Activities that talk to DuckDB over JDBC. `store` and `summarize` wrap their SQL in custom spans created via a shared `Tracer`, recording exceptions and error status on failure.
- **OpenTelemetry setup in `main`** — `AutoConfiguredOpenTelemetrySdk` builds an SDK from `OTEL_*` environment variables and registers it as the global instance (so the Activity spans can find it). An `OpenTracingShim` bridges it to the OpenTracing API that Temporal's interceptors use.
- **Temporal setup in `main`** — the `WorkflowClient` gets an `OpenTracingClientInterceptor` (a span when a Workflow is started) and the `WorkerFactory` gets an `OpenTracingWorkerInterceptor` (Workflow and Activity spans, linked to the client span). The Worker runs on the `dash0-quickstart` Task Queue with the fixed Workflow ID `dash0-ingest`, so a re-run after a crash reattaches via `WorkflowExecutionAlreadyStarted` instead of starting fresh.

Before exiting, `main` closes the OpenTelemetry SDK to flush spans still buffered by the batch exporter — otherwise the last traces may never reach Dash0.

DuckDB runs in-process; the data lives in a single file, `dash0-quickstart.duckdb`, created next to where you run the command.

## Run

Prerequisites:

- Java 21+
- Maven
- The [Temporal CLI](https://docs.temporal.io/cli), which provides `temporal server start-dev`
- A [Dash0](https://www.dash0.com) account — the onboarding page gives you your OTLP endpoint and auth token

Start a local Temporal Service in one terminal and leave it running:

```bash
temporal server start-dev
```

In another terminal, export the OpenTelemetry settings with the endpoint and token from your Dash0 onboarding page:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="https://ingress.<region>.dash0.com"
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Bearer <your-dash0-auth-token>"
export OTEL_SERVICE_NAME="dash0-quickstart"
```

Then run the demo. Let it store a few readings, press `CTRL+C` to simulate a crash, and run the same command again:

```bash
mvn compile exec:java -Dexec.mainClass="helloworkflow.Dash0Quickstart"
```

## What you'll see

On the console, the same durable-ingest story as the DuckDB sample: readings stored until you kill the process, then `Resuming existing workflow after crash...` on the second run, continuing from the next reading and ending with the summary line.

In Dash0, open **Tracing**: each workflow run appears as a trace named after the workflow, containing the client's start span, the `IngestWorkflow` span, and a span per Activity (`ReadTemperature`, `Store`, `Summarize`) — with the custom `INSERT readings` and `SELECT summary` database spans nested inside, carrying `db.system=duckdb` and the exact SQL statement. The Temporal Web UI at <http://localhost:8233> shows the same execution from Temporal's side: the Event History with every Activity result and timer.

## Going to production

- Split the single file into separate Workflow, Activity, Worker, and Starter classes, and run the Worker as its own long-lived process — the interceptor wiring (`OpenTracingClientInterceptor` on clients, `OpenTracingWorkerInterceptor` on worker factories) stays exactly the same.
- Set the `OTEL_*` variables through your deployment platform's secret/config management rather than a shell session, and set a distinct `OTEL_SERVICE_NAME` per service so client and worker are distinguishable in Dash0.
- The same OTLP configuration works for any OpenTelemetry-compatible backend — Dash0 is configured purely through the environment, not code.
- Consider adding Temporal's Micrometer/OpenTelemetry **metrics** alongside tracing for worker health (task queue latency, activity failures) — traces show individual runs, metrics show the fleet.
