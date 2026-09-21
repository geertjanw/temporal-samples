# PetClinic Reminder Quickstart

A durable **appointment reminder** in Java with **Temporal**, extracted from the Spring PetClinic "book a visit" flow. When a visit is booked, a Workflow starts and then **waits — durably — until the day before the appointment**, then sends the reminder. The wait survives process restarts, crashes, and redeploys.

## Why this sample?

The everyday way to build reminders is a `reminders` table plus a cron/Quartz job that polls "what's due now," plus your own bookkeeping for retries, idempotency, and crash recovery. Temporal replaces all of that with ordinary code that says *"sleep until due, then send."*

- **`Workflow.sleep(...)`** is a *durable timer*. It is recorded in Temporal's Event History and keeps counting down even while no process is alive to wait for it — so a reminder scheduled three months out simply works, with no scheduler and no database of your own.
- **Activities** (the actual send) are retried automatically on failure.
- A **deterministic Workflow ID** (`reminder-<pet>-<date>`) means booking the same visit twice does not create a duplicate reminder.

## How it works

The code mirrors a real Temporal application's file layout (Workflow and Activity split into interface + implementation, with separate Worker and Starter processes):

| File | Role |
|------|------|
| [`AppointmentReminderWorkflow.java`](src/main/java/petclinic/reminder/AppointmentReminderWorkflow.java) | Workflow interface: `scheduleReminder(...)` plus a `status()` query |
| [`AppointmentReminderWorkflowImpl.java`](src/main/java/petclinic/reminder/AppointmentReminderWorkflowImpl.java) | Computes "one day before the visit," calls `Workflow.sleep()` (the durable timer), then invokes the Activity |
| [`ReminderActivities.java`](src/main/java/petclinic/reminder/ReminderActivities.java) / [`...Impl.java`](src/main/java/petclinic/reminder/ReminderActivitiesImpl.java) | The side-effecting send (prints the message a real app would SMS/email) |
| [`ReminderInput.java`](src/main/java/petclinic/reminder/ReminderInput.java) | Serializable payload (owner, pet, phone, visit date, description) |
| [`ReminderWorker.java`](src/main/java/petclinic/reminder/ReminderWorker.java) | Long-running Worker that polls the Task Queue and runs the code |
| [`BookVisit.java`](src/main/java/petclinic/reminder/BookVisit.java) | Starts a reminder Workflow — the stand-in for PetClinic's `VisitController` booking a visit |

Two constants tie it together: the **Task Queue name** (`petclinic-reminders`) is how the Worker and the Workflow/Activity stubs find each other, and the **Workflow ID** is what dedupes repeat bookings.

The Workflow stays **deterministic** — it uses `Workflow.currentTimeMillis()` and `Workflow.sleep()` (never `Instant.now()` / `Thread.sleep`) and does all I/O in the Activity. Console output from the Workflow is guarded with `Workflow.isReplaying()` so it prints once, not again on every history replay.

## Run

Prerequisites:

- Java 21+
- Maven
- The [Temporal CLI](https://docs.temporal.io/cli), which provides `temporal server start-dev`

**1. Start a local Temporal Service** in one terminal and leave it running:

```bash
temporal server start-dev
```

**2. Start the Worker** in a second terminal and leave it running:

```bash
mvn compile exec:java
```

**3. Book a visit** in a third terminal. With no arguments it books a visit for *tomorrow*, which makes the reminder due immediately so you see it fire in seconds:

```bash
mvn compile exec:java -Dexec.mainClass=petclinic.reminder.BookVisit
```

## What you'll see

The **Worker** terminal prints the reminder as it fires:

```
Reminder scheduled for George Franklin's pet Leo — waiting PT0S until it is due
Sending appointment reminder to 6085551023 -> Reminder for George Franklin: Leo has a vet appointment on 2026-09-22 (Annual checkup and vaccinations)
```

### See the durable timer wait

Book a visit further in the future and the Workflow parks on its durable timer instead of firing:

```bash
mvn compile exec:java -Dexec.mainClass=petclinic.reminder.BookVisit -Dexec.args="2026-12-24 Dental cleaning"
```

Query the still-waiting Workflow — no application code involved, straight from the CLI:

```bash
temporal workflow query --workflow-id reminder-leo-2026-12-24 --type status
# => QueryResult  "WAITING"
```

Now **stop the Worker** (`CTRL+C`) and **start it again** (`mvn compile exec:java`). The timer kept counting on the server the whole time; the reminder is still scheduled and will fire on its due date. Nothing was lost — durability is a property of the platform, not code in this sample.

Watch it all in the Temporal Web UI at <http://localhost:8233>: the Event History shows the `TimerStarted`/`TimerFired` events and the Activity result.

## Interact with a real, unmodified PetClinic

`BookVisit` is a stand-in that hardcodes one visit. To drive reminders from a **real Spring PetClinic without adding a single line to it**, run [`PetClinicBridge.java`](src/main/java/petclinic/reminder/PetClinicBridge.java).

PetClinic is a server-rendered MVC app with no write API and no Temporal code. The only integration surface that requires *zero* changes to it is the database it already persists visits to. The bridge:

- Connects (JDBC) to PetClinic's Postgres and polls the `visits` table joined to `pets` and `owners`.
- Starts one `AppointmentReminderWorkflow` per visit, with a deterministic Workflow ID `reminder-visit-<id>` and a `REJECT_DUPLICATE` reuse policy — so re-seeing the same visit on the next poll is a harmless no-op. Each booked visit yields exactly one reminder.

```
PetClinic (unmodified)  ──writes visits──▶  Postgres  ◀──polls──  PetClinicBridge  ──starts workflows──▶  Temporal
                                                                                                              │
                                                                              ReminderWorker ──runs timer/activity─┘
```

Connection defaults to PetClinic's shipped Postgres (`jdbc:postgresql://localhost/petclinic`, user/pass `petclinic`); override with `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASS`.

### Run the integrated stack

PetClinic must use a **shared database** (not its default in-memory H2), so the bridge can read what it writes. Bring things up in this order:

**1. Postgres** — from the `pet-clinic` checkout:

```bash
docker compose up postgres
```

**2. Temporal Service:**

```bash
temporal server start-dev
```

**3. Reminder Worker** — from this module:

```bash
mvn compile exec:java
```

**4. PetClinic with the Postgres profile** (runtime flag only — no file changes) — from the `pet-clinic` checkout:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

**5. The bridge** — from this module:

```bash
mvn compile exec:java -Dexec.mainClass=petclinic.reminder.PetClinicBridge
```

Now open PetClinic at <http://localhost:8080>, book a visit for an owner's pet, and within ~5 seconds the bridge starts its reminder Workflow — visible in the Worker terminal and the Temporal Web UI at <http://localhost:8233>.

## From bridge to embedded (production shape)

The bridge keeps PetClinic pristine, which is ideal for a demo you don't own. If you *do* own the app, the tighter integration is to start the Workflow inline:

- Expose a `WorkflowClient` and `Worker` as Spring beans (a small `@Configuration`) instead of the plain `main` methods here.
- Have `VisitController.processNewVisitForm(...)` call a `@Service` that runs `WorkflowClient.start(workflow::scheduleReminder, input)` right after saving the visit — no polling, no database dependency.

The Workflow and Activity definitions are identical either way — the durable logic is independent of both the web framework and how the visit reaches it.
