# Temporal Samples

A collection of small, self-contained **Java/Maven** projects, each demonstrating how [Temporal](https://temporal.io) (durable workflow orchestration) combines with another technology or solves a specific problem. Every sample runs standalone and follows the same structure, so once you've read one, you can navigate them all.

## Samples

| Sample | Combines Temporal with | What it demonstrates |
|--------|------------------------|----------------------|
| [`shiro-quickstart`](shiro-quickstart/) | **Apache Shiro** | Per-user authentication and authorization in durable workflows: edge login gates workflow starts, activities re-check permissions at execution time, denials fail non-retryably. |
| [`fraud-detection-sample`](fraud-detection-sample/) | *\<fill in: e.g. rules engine / ML scoring\>* | *\<fill in: one sentence on the fraud-detection scenario\>* |

<!-- Add new samples as rows here, keeping descriptions to one sentence. -->

## Prerequisites

- **JDK 17+** and **Maven 3.8+**
- Optional, for the Web UI: the [Temporal CLI](https://docs.temporal.io/cli) (macOS: `brew install temporal`)

## Running a sample

Every sample supports the same two modes:

**Quick mode (default)** — runs against Temporal's in-process test server; nothing to install, but no Web UI:

```bash
cd <sample-directory>
mvn compile exec:java
```

**Server mode** — runs against a real local Temporal server so executions are visible in the Web UI. In one terminal:

```bash
temporal server start-dev
```

(add `--db-filename temporal.db` to keep history across restarts), then in another:

```bash
cd <sample-directory>
mvn compile exec:java -Dtemporal.mode=server
```

and open http://localhost:8233.

Samples include an `nbactions.xml`, so in **Apache NetBeans** plain *Run Project (F6)* runs quick mode, and *Run Maven → Run (Temporal server + Web UI)* runs server mode.

## Structure of every sample

Each sample directory contains a `README.md` with the same sections, in the same order:

1. **Title + one-liner** — what it combines/demonstrates
2. **Why this sample?** — the problem and why the combination is useful
3. **How it works** — architecture, key classes, demo users/config
4. **Run** — quick mode and server mode commands
5. **What you'll see** — expected console output and Web UI results
6. **Going to production** — what to change for real deployments

New samples should start from [`SAMPLE_README_TEMPLATE.md`](SAMPLE_README_TEMPLATE.md).

## License

<!-- fill in: e.g. Apache License 2.0 -->
