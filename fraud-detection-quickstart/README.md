# Credit Card Fraud Detection with Temporal.io

Combines the Deep Netts neural network from
[deepnetts/CreditCardFraudDetection](https://github.com/deepnetts/CreditCardFraudDetection)
with [Temporal.io](https://temporal.io) durable workflows, in a single Java source file.

## What it does

| Component | Purpose |
|---|---|
| `TrainingWorkflow` | Durably trains the Deep Netts fraud model from the CSV. If the worker crashes mid-training, Temporal retries automatically (up to 3 attempts). |
| `ScreeningWorkflow` | Scores one transaction. Low score → auto-approved. High score → notifies the fraud team and waits (up to 48 hours, at zero cost) for a human `reviewDecision` signal. |
| `FraudActivities` | All Deep Netts training/inference and side effects live here — workflow code stays deterministic. |
| `main()` | Starts a worker, trains the model, screens the first transaction from the CSV, and simulates a reviewer approving a flagged transaction after 5 seconds. |

## Prerequisites

- Java 11+
- Maven 3.6+
- [Temporal CLI](https://docs.temporal.io/cli) for the local dev server
- `creditcard-balanced.csv` from the original repo in the project root
  (copy it over, or unzip `creditcard.zip` from that repo)

## Run

The app takes a command as its first argument — no simulation, all interactions
go through real Temporal workflows and signals.

```bash
# Terminal 1: Temporal dev server (Web UI at http://localhost:8233)
temporal server start-dev

# Terminal 2: the worker — leave it running
mvn compile exec:java -Dexec.args="worker"

# Terminal 3: operations
mvn compile exec:java -Dexec.args="train"        # train the fraud model
mvn compile exec:java -Dexec.args="screen 1"     # screen CSV data row 1
```

If the screened transaction is flagged, its workflow durably waits (up to 48h)
for a human decision. The `screen` command prints the workflow ID and the exact
commands to decide:

```bash
mvn compile exec:java -Dexec.args="approve txn-screening-row-1"
mvn compile exec:java -Dexec.args="reject txn-screening-row-1"
mvn compile exec:java -Dexec.args="result txn-screening-row-1"   # final decision
```

You can also approve/reject from the Temporal Web UI (open the running
workflow → Send Signal → `reviewDecision` with input `true` or `false`) or the CLI:

```bash
temporal workflow signal --workflow-id txn-screening-row-1 \
  --name reviewDecision --input 'true'
```

List transactions waiting for review — a mini case dashboard that queries each
running workflow for its live status and fraud score:

```bash
mvn compile exec:java -Dexec.args="pending"
# txn-screening-row-900  ->  status=AWAITING_REVIEW, fraudScore=0.93, threshold=0.5
```

The same information is available in the Web UI: open the running workflow and
use the **Queries** tab to run `caseStatus`.

Tip: the CSV is ordered, so early rows are legitimate transactions
(auto-approved) and later rows are frauds — try `screen 900` to get one that
needs review.

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
- The model and scaler are written atomically, so an interrupted run cannot
  leave corrupt files behind.
- The training workflow ID is fixed (`fraud-model-training`); screening
  workflow IDs are `txn-screening-row-<n>`. Re-screening the same row while
  its workflow is still running is rejected by Temporal (duplicate ID) —
  decide the pending one first.
