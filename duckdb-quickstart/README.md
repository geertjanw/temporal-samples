# Durable Ingest into DuckDB in Java

The simplest **Temporal**-plus-**[DuckDB](https://duckdb.org)** scenario in Java, in a single file: a Workflow orchestrating durable steps that read data and persist it into an embedded DuckDB database, then run one analytical query over the result.

## Why this sample?

Ingest pipelines fail in the middle. The interesting question is what happens then: do you re-read data you already stored, write duplicate rows, or lose progress entirely? This sample shows how Temporal answers that with no recovery code of your own.

Temporal persists Workflow progress to its own Event History. Each Activity result — including every `store` call that committed a row to DuckDB — is a durable Event. When the process dies, both the Event History and the DuckDB file are unaffected. Restarting the process starts a new Worker, and Temporal hands it the in-flight Workflow, which resumes from the last durable step. Readings already stored are not written again, because Temporal replays their completed `store` Activities from history rather than re-running them.

## How it works

A Workflow takes ten (simulated) temperature readings, ten seconds apart, and stores each one as a row in a DuckDB table. When all ten are in, it runs a single SQL query for the minimum, maximum, and average, and reports the result. The entire program is in one Java source file, [`DuckDBQuickstart.java`](src/main/java/helloworkflow/DuckDBQuickstart.java). There are three parts:

- **`IngestWorkflow`** — a Workflow that, for each reading, calls the `readTemperature` Activity and then the `store` Activity, sleeps ten seconds, and finally calls `summarize`.
- **`IngestActivities`** — Activities that talk to DuckDB over JDBC: `createTable`, `readTemperature` (a stand-in for real hardware or an HTTP call), `store`, and `summarize`.
- **`main`** — starts a Worker on the `duckdb-quickstart` Task Queue and starts (or re-attaches to) the Workflow with a fixed Workflow ID, `duckdb-ingest`.

DuckDB runs in-process — there is no separate database server. The data lives in a single file, `duckdb-quickstart.duckdb`, created next to where you run the command.

Design points worth noticing:

- **All JDBC calls happen inside Activities, never directly in the Workflow method.** Workflow code must be deterministic so Temporal can replay it, and I/O like a database call is not. Activities are the boundary for anything non-deterministic or side-effecting, so every touch of DuckDB — creating the table, inserting a row, running the query — is an Activity.
- **The `store` Activity is idempotent** (`INSERT OR IGNORE` on a primary key). Temporal guarantees at-least-once Activity execution, so an Activity that commits and then fails to report back can run a second time — the idempotent insert makes that harmless.
- **Each Activity opens and closes its own JDBC connection.** DuckDB allows a single read-write connection to a file at a time, and short-lived connections keep the demo simple; a production Worker would typically hold a pooled connection.
- **`Workflow.sleep`, not `Thread.sleep`.** A Workflow sleep is durable: it is recorded as a timer in the Event History, so it keeps counting down even while no process is alive to wait for it.
- **The fixed Workflow ID enables reattachment.** On a restart, the ID `duckdb-ingest` is already in use by the run still in flight, so the start attempt throws `WorkflowExecutionAlreadyStarted`. That is not an error to fix — it's the signal to attach to the existing run and wait for its result.

## Run

Prerequisites:

- Java 21+
- Maven
- The [Temporal CLI](https://docs.temporal.io/cli), which provides `temporal server start-dev`

Start a local Temporal Service in one terminal and leave it running:

```bash
temporal server start-dev
```

In another terminal, run the demo once. Let it store a few readings, then press `CTRL+C` to simulate a crash:

```bash
mvn compile exec:java -Dexec.mainClass="helloworkflow.DuckDBQuickstart"
```

Then run the same command again.

## What you'll see

The first run stores readings until you kill it:

```
Stored reading 1 of 10: 21.4 °C
Stored reading 2 of 10: 17.9 °C
Stored reading 3 of 10: 23.1 °C
^C
```

On the second run, a new Worker attaches to the in-flight Workflow, Temporal replays its Event History, and ingest continues — the rows already written to DuckDB stay put, and ingest resumes from the next reading:

```
Resuming existing workflow after crash...
Stored reading 4 of 10: 19.6 °C
Stored reading 5 of 10: 22.8 °C
...
Summary: 10 readings — min 16.2 °C, max 24.7 °C, avg 20.5 °C
```

Things to try:

- After a run finishes, open the file with the DuckDB CLI and query it yourself: `duckdb duckdb-quickstart.duckdb "SELECT * FROM readings"`.
- Watch the Workflow Execution details in the Temporal Web UI at <http://localhost:8233> while it is paused between runs.
- Change the reading count or the sleep interval.
- Delete `duckdb-quickstart.duckdb` and `temporal server start-dev`'s state to start completely fresh.

## Going to production

Keeping everything in one file is a deliberate simplification for learning. A real Temporal application would normally split these concerns across separate files:

- `IngestActivities.java` — the Activity interface.
- `IngestActivitiesImpl.java` — the Activity implementation.
- `IngestWorkflow.java` — the Workflow interface.
- `IngestWorkflowImpl.java` — the Workflow implementation.
- `Worker.java` — a process that registers the Workflow and Activity implementations and polls the Task Queue. In production this Worker runs on its own and stays up.
- `Starter.java` — a separate process (or CLI command, or web request handler) that starts the Workflow and, optionally, waits for its result.

The key difference is that the Worker and the Starter are usually two independent processes, not one. Here they share a `main` method only so the demo is a single command you can run twice. In production you'd also replace the per-call JDBC connections with a pooled connection held by the Worker.
