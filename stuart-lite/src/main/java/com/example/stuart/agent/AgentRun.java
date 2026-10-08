package com.example.stuart.agent;

import java.util.List;
import java.util.Set;

/**
 * Everything one agent run needs. The loop itself knows nothing about Jira, git or phases:
 * callers parameterize it with prompts and a tool set ("one agent core, many workflows").
 *
 * @param systemPrompt  stable instructions (kept at the start of the prompt so it stays cacheable)
 * @param userPrompt    the task for this run
 * @param tools         objects whose {@code @Tool} methods the model may call
 * @param mutatingTools names of tools that change state (a write resets "already read" tracking)
 * @param maxIterations hard cap on model turns
 * @param tokenBudget   hard cap on cumulative prompt + completion tokens
 * @param maxNudges     how many guardrail warnings the model gets before a hard stop
 */
public record AgentRun(
        String systemPrompt,
        String userPrompt,
        List<Object> tools,
        Set<String> mutatingTools,
        int maxIterations,
        long tokenBudget,
        int maxNudges) {
}
