# Temporal Samples

A collection of small, self-contained **Java/Maven** projects, each showing how [Temporal](https://temporal.io) (durable workflow orchestration) solves a specific problem or combines with another technology. Most samples fit in a single Java source file, and each has its own README explaining why the combination is useful, how it works, how to run it, and what you'll see.

## Samples

In rough learning order — start at the top:

| Sample | Combines Temporal with | What it demonstrates |
|--------|------------------------|----------------------|
| [`sensor-quickstart`](sensor-quickstart/) | — (pure Temporal) | The simplest crash-recovery scenario: a workflow polls a simulated sensor ten times; kill it with `CTRL+C` mid-run, re-run, and it resumes exactly where it stopped — no save/load code of your own. |
| [`duckdb-quickstart`](duckdb-quickstart/) | **DuckDB** | Durable ingest into an embedded database: each reading is stored as a row in DuckDB, a crash loses nothing, and a final SQL query summarizes the result. |
| [`dash0-quickstart`](dash0-quickstart/) | **DuckDB + OpenTelemetry / Dash0** | The DuckDB ingest scenario made observable: Temporal's OpenTracing interceptors plus custom spans export workflow, activity, and database traces via OTLP to [Dash0](https://www.dash0.com). |
| [`fraud-detection-quickstart`](fraud-detection-quickstart/) | **Deep Netts (neural network)** | Durable ML workflows with a human in the loop: one workflow trains a credit-card fraud model, another scores transactions — flagged ones wait up to 48 hours (at zero cost) for a human approve/reject signal, driven from a CLI or the Web UI. |
| [`shiro-quickstart`](shiro-quickstart/) | **Apache Shiro** | Per-user authentication and authorization in durable workflows: edge login gates workflow starts, activities re-check permissions at execution time, and denials fail non-retryably. |

## Prerequisites

- **Java** — 21+ for most samples (`fraud-detection-quickstart` needs 11+, `shiro-quickstart` 17+; see each pom)
- **Maven 3.6+**
- **[Temporal CLI](https://docs.temporal.io/cli)** (macOS: `brew install temporal`) — provides the local dev server and Web UI

## Running a sample

Each sample's README has its exact commands, but the shape is always the same. Start a local Temporal Service in one terminal and leave it running:

```bash
temporal server start-dev
```

(Add `--db-filename temporal.db` to keep workflow history across server restarts.) Then, in another terminal, run the sample from its directory with `mvn compile exec:java` plus the arguments its README specifies — for example:

```bash
cd sensor-quickstart
mvn compile exec:java -Dexec.mainClass="helloworkflow.SensorQuickstart"
```

Watch what happens in the Web UI at http://localhost:8233 — several samples are specifically designed to be observed there (crash recovery in the event history, workflows durably waiting for signals, denied permissions recorded as failures).

The exception to needing a server: `shiro-quickstart` defaults to Temporal's in-process test server (plain `mvn compile exec:java`, nothing else running) and connects to the real server only with `-Dtemporal.mode=server`.

`fraud-detection-quickstart` and `shiro-quickstart` include an `nbactions.xml`, so in **Apache NetBeans** their run/debug variants are available directly from the project's context menu.

## Structure of every sample README

1. **Title + one-liner** — what it combines/demonstrates
2. **Why this sample?** — the problem and why the combination is useful
3. **How it works** — the scenario, the key workflow/activity classes, demo data or users
4. **Run** — prerequisites and exact commands
5. **What you'll see** — expected console output and what to look for in the Web UI
6. **Going to production** — what to change for real deployments
