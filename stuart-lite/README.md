# stuart-lite

A minimal Java take on **Stuart**, the "extra pair of hands" AI developer described in
[*Stuart: An Extra Pair of Hands for Dev Teams*](https://medium.com/skyro-tech/stuart-an-extra-pair-of-hands-for-dev-teams-7e96f652a72e)
by Sulyz Andrei of Skyro Tech.

You assign it a task. It clones the repo, plans, implements, writes tests and opens a merge request
(here, a local branch and commit). Then it waits, possibly for days, for review comments and fixes
each one. Anything it can't work out, it asks about.

**Spring AI** provides the model, the tool calling and an agent loop that this code controls itself.
**Temporal** makes the long-running process durable: a crash or redeploy resumes the workflow where it
stopped, and every LLM call and git operation is an activity with retries, visible in the Temporal UI.

## Architecture

```
REST (stands in for the Jira / GitLab / Slack webhooks)
   │ start / signal / query
   ▼
DevTaskWorkflow (Temporal, deterministic)
   prepareWorkspace ─► PLAN ─(CLARIFICATION NEEDED? wait for answerClarification signal)
                     ─► IMPLEMENT ─► PLAN_TESTS ─► WRITE_TESTS   (skipped with label "no-tests")
                     ─► openMergeRequest (description has a budget table)
                     ─► review loop: reviewComment signal ─► ADDRESS_REVIEW ─► commit ─► reply
                                     approve signal ─► done (a human merges)
   │ every step is an activity
   ▼
DevActivitiesImpl ──► AgentLoopService (generic ReAct loop, one per phase)
                         ├─ ChatModel (Spring AI, OpenAI-compatible: OpenRouter / OpenAI / Ollama…)
                         ├─ tools: ReadTools (readFile, listFiles, grep)
                         │         WriteTools (writeFile, editFile, deleteFile) – implementation phases only
                         └─ Guardrails (duplicate calls, cycles, token budget → nudge → hard stop)
```

| Idea from the article | Where it lives here |
|---|---|
| Agent loop is "just a for loop"; the harness, not the model, executes tools | `agent/AgentLoopService` (Spring AI internal tool execution is **off**) |
| Tools are Java methods with detailed descriptions plus defensive checks | `tools/ReadTools`, `tools/WriteTools`, `tools/Workspace` |
| One generic agent core, many workflows | `AgentRun` takes the prompts, tools and limits as parameters; the loop has no domain knowledge |
| Phased pipeline: plan → implement → plan tests → write tests | `workflow/Phase`, `DevTaskWorkflowImpl` |
| Read-only phases have no write tools | `DevActivitiesImpl.runPhase` |
| Labels configure behavior (`no-tests`) | `DevTask.labels` |
| Tech review: ask clarification questions, then produce a plan that is reused later | `CLARIFICATION NEEDED` + `answerClarification` signal; label `tech-review` stops after the plan; pass `plan` to skip planning |
| Append-only context with a dynamic "memory" tail and a tool ledger | `AgentLoopService.memoryTail` |
| Guardrails: duplicates, A→B→C cycles, stale re-reads, budget; nudge then hard stop | `agent/Guardrails` |
| Project docs as the agent's memory | `AGENTS.md` / `STUART.md` in the target repo, embedded by `PromptFactory` |
| Code anchors such as `[AGR-1]` for cheap entry-point lookup | System prompt + `grep` tool |
| Durable execution, retries, heartbeats, signals for review feedback | `DevTaskWorkflowImpl`, `DevActivities` |
| Isolated workspace, repo-bounded paths, humans merge | `prepareWorkspace` (fresh clone per task), `Workspace.resolve`, workflow ends at "approved" |
| Budget table in the MR description | `describeMergeRequest` |

## Run it

Prerequisites: Java 21, Maven, git, the [Temporal CLI](https://docs.temporal.io/cli), and an API key
for an OpenAI-compatible endpoint (OpenRouter by default).

```bash
# 1. Temporal dev server (UI on http://localhost:8233)
temporal server start-dev

# 2. A toy repository to work on
./demo/create-sample-repo.sh            # creates /tmp/stuart-sample-repo

# 3. Stuart
export OPENROUTER_API_KEY=sk-or-...
# optional: export LLM_MODEL=openai/gpt-4.1   (or LLM_BASE_URL=https://api.openai.com with OPENAI_API_KEY)
mvn spring-boot:run
```

Assign a task:

```bash
curl -s localhost:8080/tasks -H 'Content-Type: application/json' -d '{
  "key": "AGR-2",
  "title": "Expose customerId in AgreementResponse",
  "description": "Consumers of [AGR-1] need the customerId of each agreement. Add it to AgreementResponse and fill it in AgreementService.",
  "repoUrl": "/tmp/stuart-sample-repo",
  "labels": []
}'
```

Follow along with `curl -s localhost:8080/tasks/AGR-2 | jq`, or in the Temporal UI, where you can see each
activity's input and output. Then act as the reviewer:

```bash
# if state == WAITING_FOR_CLARIFICATION
curl -s localhost:8080/tasks/AGR-2/clarification -H 'Content-Type: application/json' -d '{"answer":"Yes, keep the field name customerId"}'

# review comments become signals; each one becomes an ADDRESS_REVIEW phase plus a commit
curl -s localhost:8080/tasks/AGR-2/comments -H 'Content-Type: application/json' \
     -d '{"threadId":"t1","body":"Please put customerId before amount in the record"}'

curl -s -X POST localhost:8080/tasks/AGR-2/approve
```

The result is on the `stuart/agr-2` branch of the clone under `${java.io.tmpdir}/stuart-workspaces/`.
Set `stuart.git-push=true` to push the branch to the origin remote.

Tests: `mvn test` runs the guardrail unit tests and a workflow test against Temporal's in-memory test
server with fake activities, so no LLM or git is needed.

## What's deliberately left out (and where it would go)

- **Jira / Confluence / GitLab / Slack**: the REST controller stands in for webhooks.
  `openMergeRequest` is the place to call the GitLab or GitHub API and post the link to Slack. A
  `fetchTask` activity that reads the ticket and its linked spec would replace the task posted in the request.
- **Running the build and tests**: add a `runCommand` tool with an allowlist (for example `mvn -q test`) to the
  implementation phases, ideally in a sandbox.
- **Prompt caching**: the prompt is already laid out for it (stable prefix, dynamic tail). Turning it on is
  provider-specific: Anthropic needs `cache_control` markers, for example via Spring AI's Anthropic module.
- **Semantic code tools** (Spoon / Jedi in the article): add them as another tool class.
- **Several workers**: workspaces sit on the local disk of the worker that cloned them. With more than one
  worker, route a task's activities to one host (a per-worker task queue or a session), or use shared storage.
- **Retries of write phases** continue on the partly edited working tree. That's acceptable for a demo; you
  could reset to the last commit before retrying.
