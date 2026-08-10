# Hello World Crash Recovery in Java

The simplest Temporal-based crash-recovery scenario in Java, in a single file.
It's a starting point for anyone new to Temporal, showing crash recovery with
no save/load code of your own.

## What it does

A Workflow polls a (simulated) temperature sensor ten times, ten seconds apart,
then reports the minimum, maximum, and average. Partway through, you kill the
process with `CTRL+C`. When you re-run the same command, a new Worker resumes
the Workflow from where it stopped. The readings already taken are preserved
rather than re-taken — no progress is lost and no reading is repeated.

The entire program is in one Java source file,
[`SensorQuickstart.java`](src/main/java/helloworkflow/SensorQuickstart.java).
There are three parts:

- **`SensorWorkflow`** — a Workflow that calls the `readTemperature` Activity for
  each reading and sleeps ten seconds between readings.
- **`SensorActivities`** — an Activity that stands in for real hardware or an HTTP
  call by returning a random temperature between 15 °C and 25 °C.
- **`main`** — starts a Worker on the `sensor-quickstart` Task Queue and starts
  (or re-attaches to) the Workflow with a fixed Workflow ID, `sensor-monitor`.

Two constants matter:

- The **Task Queue name** is how the Worker and the Workflow find each other.
- The **fixed Workflow ID** is what lets a second run of the same command
  reattach to the run already in progress instead of starting a fresh one.

## Prerequisites

- Java 21+
- Maven
- The Temporal CLI, which provides `temporal server start-dev`

## Running it

Start a local Temporal Service in one terminal and leave it running:

```bash
temporal server start-dev
```

In another terminal, run the demo once. It reads three times, and then you
press `CTRL+C` to simulate a crash:

```bash
mvn compile exec:java -Dexec.mainClass="helloworkflow.SensorQuickstart"
```

```
Reading 1 of 10: 21.4 °C
Reading 2 of 10: 17.9 °C
Reading 3 of 10: 23.1 °C
^C
```

Run the same command again. A new Worker attaches to the in-flight Workflow,
Temporal replays its Event History, and the run continues:

```
Resuming existing workflow after crash...
Reading 1 of 10: 21.4 °C
Reading 2 of 10: 17.9 °C
Reading 3 of 10: 23.1 °C
Reading 4 of 10: 19.6 °C
Reading 5 of 10: 22.8 °C
...
Summary: 10 readings — min 16.2 °C, max 24.7 °C, avg 20.5 °C
```

Look closely at the first three lines of the second run. Replay re-executes your
Workflow method from the top, which is why those `System.out.printf` calls fire a
second time — but the temperatures are identical to the first run, down to the
decimal. The sensor is random, so identical values can only mean one thing:
those readings were not taken again. They were read back from the Event History.
(In production you'd use `Workflow.getLogger`, which suppresses output during
replay, or guard on `Workflow.isReplaying()`.)

## Why it recovers

Temporal persists Workflow progress to its own Event History. When the process
dies, that Event History is unaffected. Restarting the process starts a new
Worker, and Temporal hands it the in-flight Workflow, which resumes from the last
durable step.

On the restart, the Workflow ID `sensor-monitor` is already in use by the run
still in flight, so the start attempt throws `WorkflowExecutionAlreadyStarted`.
That is not an error to fix — it's the signal to attach to the existing run and
wait for its result.

You do not write any checkpoint or resume logic. There's no database, no
checkpoint file, and no reconciliation code — the recovery is a property of the
platform, not code in the demo.

Use `Workflow.sleep` rather than `Thread.sleep`. A Workflow sleep is durable: it
is recorded as a timer in the Event History, so it keeps counting down even while
no process is alive to wait for it.

## A note on structure

Keeping everything in one file is a deliberate simplification for learning. A
real Temporal application would normally split these concerns across separate
files:

- `SensorActivities.java` — the Activity interface.
- `SensorActivitiesImpl.java` — the Activity implementation.
- `SensorWorkflow.java` — the Workflow interface.
- `SensorWorkflowImpl.java` — the Workflow implementation.
- `Worker.java` — a process that registers the Workflow and Activity
  implementations and polls the Task Queue. In production this Worker runs on its
  own and stays up.
- `Starter.java` — a separate process (or CLI command, or web request handler)
  that starts the Workflow and, optionally, waits for its result.

The key difference is that the Worker and the Starter are usually two independent
processes, not one. Here they share a `main` method only so the demo is a single
command you can run twice.

## Things to try

- Watch the Workflow Execution details in the Temporal Web UI at
  <http://localhost:8233> while it is paused between runs.
- Change the reading count or the sleep interval.
- Kill it more than once, or leave it dead for a minute before restarting. The
  durable timer keeps running while no process is alive, so the Workflow is often
  several readings further along than where you left it.
