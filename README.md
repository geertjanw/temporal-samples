# Temporal Samples

A collection of quickstart projects demonstrating [Temporal](https://temporal.io) —
a durable execution platform for building reliable, fault-tolerant workflows —
in a variety of practical scenarios.

## Samples

| Sample | Description |
|---|---|
| [dash0-quickstart](./dash0-quickstart) | Instrumenting Temporal workflows with OpenTelemetry and sending traces, metrics, and logs to [Dash0](https://www.dash0.com) for observability. |
| [duckdb-quickstart](./duckdb-quickstart) | Using Temporal workflows to orchestrate data processing and analytics with [DuckDB](https://duckdb.org). |
| [fraud-detection-quickstart](./fraud-detection-quickstart) | A fraud detection pipeline built on Temporal, showing how to model multi-step transaction screening as a durable workflow. |
| [sensor-quickstart](./sensor-quickstart) | Processing sensor/IoT data streams with Temporal, handling ingestion and long-running monitoring reliably. |

## Prerequisites

- A local Temporal server — the easiest option is the
  [Temporal CLI](https://docs.temporal.io/cli):

```bash
  temporal server start-dev
```

  This starts a dev server with the Web UI at http://localhost:8233.

## Getting Started

Each sample is self-contained. Navigate into a sample directory and follow the
instructions in its README:

```bash
cd fraud-detection-quickstart
```

In general, each quickstart involves:

1. Starting a **worker** that hosts the workflow and activity implementations.
2. Running a **starter** (or sending a request) that kicks off a workflow execution.
3. Observing the workflow in the Temporal Web UI.

## Learn More

- [Temporal Documentation](https://docs.temporal.io)
- [Temporal SDKs](https://docs.temporal.io/dev-guide)
- [Official temporalio/samples repositories](https://github.com/temporalio)

## License

See individual samples for license information.
