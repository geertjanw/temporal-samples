# Credit Card Fraud Detection with Temporal.io

Combines the Deep Netts neural network from [deepnetts/CreditCardFraudDetection](https://github.com/deepnetts/CreditCardFraudDetection) with [Temporal.io](https://temporal.io) durable workflows, in a single Java source file.

## What it does

| Component           | Purpose                                                                                                                                                               |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `TrainingWorkflow`  | Durably trains the Deep Netts fraud model from the CSV. If the worker crashes mid-training, Temporal retries automatically (up to 3 attempts).                        |
| `ScreeningWorkflow` | Scores one transaction. Low score → auto-approved. High score → notifies the fraud team and waits (up to 48 hours, at zero cost) for a human `reviewDecision` signal. |
| `FraudActivities`   | All Deep Netts training/inference and side effects live here — workflow code stays deterministic.                                                                     |
| `main()`            | A command-driven CLI: `worker`, `train`, `screen`, `frauds`, `approve`, `reject`, `result`, `pending`. No simulation — every command talks to real Temporal workflows and signals. |

## Prerequisites

- Java 11+
- Maven 3.6+
- [Temporal CLI](https://docs.temporal.io/cli) for the local dev server
- `creditcard-balanced.csv` from the original repo in the project root
  (copy it over, or unzip `creditcard.zip` from that repo)

## Run

The app takes a command as its first argument.

```
# Terminal 1: Temporal dev server (Web UI at http://localhost:8233)
temporal server start-dev

# Terminal 2: the worker — leave it running
mvn compile exec:java -Dexec.args="worker"

# Terminal 3: operations
mvn compile exec:java -Dexec.args="train"        # train the fraud model
mvn compile exec:java -Dexec.args="screen 1"     # screen CSV data row 1
```

If the screened transaction is flagged, its workflow durably waits (up to 48h)
for a human decision. The `screen` command prints the ground-truth label of the
row, the workflow ID, and the exact commands to decide:

```
mvn compile exec:java -Dexec.args="approve txn-screening-row-1"
mvn compile exec:java -Dexec.args="reject txn-screening-row-1"
mvn compile exec:java -Dexec.args="result txn-screening-row-1"   # final decision
```

You can also approve/reject from the Temporal Web UI (open the running
workflow → Send Signal → `reviewDecision` with input `true` or `false`) or the CLI:

```
temporal workflow signal --workflow-id txn-screening-row-1 \
  --name reviewDecision --input 'true'
```

List transactions waiting for review — a mini case dashboard that queries each
running workflow for its live status and fraud score:

```
mvn compile exec:java -Dexec.args="pending"
# txn-screening-row-1  ->  status=AWAITING_REVIEW, fraudScore=0.93, threshold=0.5
```

The same information is available in the Web UI: open the running workflow and
use the **Queries** tab to run `caseStatus`.

Tip: in this CSV the fraud rows come **first** — early rows (e.g. `screen 1`)
are frauds that get flagged for review, while later rows (e.g. `screen 900`)
are legitimate and auto-approved. To see exactly which rows are frauds, run:

```
mvn compile exec:java -Dexec.args="frauds"
# Rows labeled as FRAUD in creditcard-balanced.csv:
# Total: 492 fraud rows
# First 20: [1, 2, 3, ...]
```

## Merging into the original repo

If you merge this into your fork of `CreditCardFraudDetection` instead of
keeping it standalone:

1. Copy `src/main/java/com/example/fraud/FraudDetectionApp.java` into the repo's `src/main/java` tree.
2. Add the `temporal-sdk` and `slf4j-simple` dependencies from this `pom.xml` to the repo's `pom.xml` (it already has `deepnetts-core`).
3. Keep the CSV in the directory you run Maven from.

## Configuration knobs (constants in `FraudDetectionApp.java`)

- `NUM_INPUTS` — number of feature columns in the CSV (default 29). Adjust if your CSV differs.
- `FRAUD_THRESHOLD` — fraud probability above which a transaction goes to human review (default 0.5).
- `MODEL_FILE` — where the trained network is saved (`fraudDetectionModel.dnet`).

## Notes

- Feature max-abs scaling is computed during training, persisted to
  `fraudDetectionScaler.bin`, and applied to every screened transaction.
- The model and scaler are saved via a temp file and moved into place —
  atomically where the OS allows it, with a plain replace as fallback. If a
  saved model ever fails to load or scores NaN, the app recovers by retraining
  from the CSV automatically (training takes ~100ms on this dataset).
- The training workflow ID is fixed (`fraud-model-training`); screening
  workflow IDs are `txn-screening-row-<n>`. Re-screening the same row while
  its workflow is still running is rejected by Temporal (duplicate ID) —
  decide the pending one first.
