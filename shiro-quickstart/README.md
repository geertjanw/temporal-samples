# Shiro + Temporal Demo

A minimal Maven project showing how to combine **Apache Shiro** (authentication/authorization) with **Temporal** (durable workflows).

## Why combine Shiro and Temporal?

Temporal solves *reliability*: workflows survive process crashes, retry failed steps automatically, and can run for days or months. But Temporal deliberately has no opinion about *who* is allowed to do *what* — any code that can reach the server can start workflows, and activities execute with whatever privileges the worker process has.

Shiro solves *identity*: who is this user, and what may they do? But Shiro's model is built around a thread-bound `Subject` in a single JVM — which breaks down exactly where Temporal shines: work that hops across processes, machines, and time.

Combining them gives you durable business processes that still enforce per-user permissions at every dangerous step:

- **Long-running work outlives the login.** An order approval might happen hours after the user's session ended. Because only the principal *name* travels in the workflow, and activities re-hydrate a `Subject` from it against the shared realm, permissions are evaluated **at execution time** — if a user's rights are revoked mid-workflow, the next privileged activity fails.
- **Defense in depth.** The edge check (`order:submit`) stops unauthorized starts cheaply; the activity check (`order:approve`) protects the actual side effect even if a workflow is started through some other path (CLI, Web UI, another service).
- **Retries don't become privilege-escalation loops.** Authorization denials are thrown as *non-retryable* failures, so Temporal records the denial in the workflow history instead of hammering the check.
- **Auditability for free.** Temporal's event history durably records which principal each workflow ran as and exactly where a denial happened — visible in the Web UI.

The demo distills this into three enforcement points:

1. **Edge (client):** the user logs in via Shiro (`shiro.ini` realm). Starting the workflow requires the `order:submit` permission.
2. **Transit (workflow):** only the *principal name* is carried in the workflow input. No passwords, tokens, or Shiro sessions — workflow inputs are persisted in Temporal's event history. Workflow code itself makes **no** Shiro calls, keeping it deterministic.
3. **Activities (workers):** the privileged `approveOrder` activity re-hydrates a Shiro `Subject` from the propagated username and checks `order:approve`. A denial throws a **non-retryable** `ApplicationFailure` (retrying won't grant permissions).

## Users

| User  | Password    | Role  | Permissions         |
|-------|-------------|-------|---------------------|
| alice | secret123   | admin | `order:*`           |
| bob   | password123 | clerk | `order:submit` only |

## Scenarios

Three scenarios run on every execution:

1. `alice` — submits and approves → workflow succeeds.
2. `bob` — may submit, but `approveOrder` fails with `AccessDenied` inside the workflow.
3. `bob` with wrong password — Shiro rejects the login at the edge; the workflow never starts.

## Run — quick mode (default)

In-process Temporal test server: nothing to install, but **no Web UI**.

```bash
mvn compile exec:java
```

In NetBeans this is plain **Run Project (F6)** / **Debug Project** (wired up in `nbactions.xml`).

## Run — server mode (with Web UI)

1. Install the [Temporal CLI](https://docs.temporal.io/cli) (macOS: `brew install temporal`).
2. Start a local dev server and leave it running:
   ```bash
   temporal server start-dev
   ```
   (Add `--db-filename temporal.db` if you want workflow history to survive restarts.)
3. Run the demo against it:
   ```bash
   mvn compile exec:java -Dtemporal.mode=server
   ```
   In NetBeans: right-click the project → **Run Maven → Run (Temporal server + Web UI)** — a debug variant is there too.
4. Open http://localhost:8233.

In the Web UI you'll see `order-ORD-1001-<timestamp>` (**Completed**) and `order-ORD-1002-<timestamp>` (**Failed** — open its event history to find the `AccessDenied` `ApplicationFailure` with zero retries). ORD-1003 never appears because Shiro rejects the login before a workflow is started. Workflow IDs get a timestamp suffix in server mode so repeated runs don't collide.

## Project notes

- `com.example.security.ShiroSecurity` — Shiro bootstrap, edge login, and `subjectFor(username)` used by activities to check permissions for a propagated principal.
- `com.example.App` — starts a worker and runs the three scenarios; picks test vs. real server via `-Dtemporal.mode`.
- The pom includes `jcl-over-slf4j`: Shiro 2.x's INI parsing uses commons-beanutils, which needs the commons-logging API at runtime; the bridge provides it and routes it through SLF4J.

## Going to production

- Run the worker (the `Worker` registration code) as its own long-lived process, separate from the client that starts workflows; point both at your Temporal cluster via `WorkflowServiceStubs.newServiceStubs(...)`.
- Replace the `shiro.ini` realm with a JDBC/LDAP realm; the activity-side check (`ShiroSecurity.subjectFor`) works the same as long as workers share the realm configuration.
- For richer identity propagation (e.g. via headers instead of workflow arguments), look at Temporal's `ContextPropagator` interface, and consider signing or verifying the propagated identity if workflow starts can come from untrusted clients.
- Enable TLS/mTLS between clients, workers, and the Temporal server — Shiro governs *application-level* permissions, not transport security.