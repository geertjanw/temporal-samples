package com.example.stuart.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the agent from spinning forever.
 * <ul>
 *   <li>Before tools run: exact duplicate calls (name + canonicalized args) and short cycles (A→B→A→B, A→B→C→A→B→C).</li>
 *   <li>After tools run: token budget thresholds.</li>
 * </ul>
 * Escalation is two-step: first a <b>nudge</b> (a message injected into the next prompt), then a <b>hard stop</b>.
 */
public final class Guardrails {

    public enum Action { CONTINUE, NUDGE, STOP }

    public record Verdict(Action action, String message) {
        static final Verdict CONTINUE = new Verdict(Action.CONTINUE, null);
    }

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Set<String> mutatingTools;
    private final long tokenBudget;
    private final int maxNudges;

    private final List<String> history = new ArrayList<>();
    private final Set<String> seenSinceLastWrite = new HashSet<>();
    private int nudgesUsed;
    private boolean budgetWarned;

    public Guardrails(Set<String> mutatingTools, long tokenBudget, int maxNudges) {
        this.mutatingTools = mutatingTools;
        this.tokenBudget = tokenBudget;
        this.maxNudges = maxNudges;
    }

    /** name + arguments with JSON keys sorted, so {"a":1,"b":2} and {"b":2,"a":1} are the same call. */
    public static String signature(String toolName, String jsonArguments) {
        String args = jsonArguments == null || jsonArguments.isBlank() ? "{}" : jsonArguments;
        try {
            Object tree = CANONICAL.readValue(args, Object.class);
            return toolName + ":" + CANONICAL.writeValueAsString(tree);
        } catch (Exception e) {
            return toolName + ":" + args.strip();
        }
    }

    /** True if this read-only call was already made and nothing has been written since (stale exploration). */
    public boolean isRepeat(String toolName, String signature) {
        return !mutatingTools.contains(toolName) && seenSinceLastWrite.contains(signature);
    }

    /** Record a call that was actually executed. */
    public void record(String toolName, String signature) {
        history.add(signature);
        if (mutatingTools.contains(toolName)) {
            seenSinceLastWrite.clear(); // files may have changed; re-reading is legitimate again
        } else {
            seenSinceLastWrite.add(signature);
        }
    }

    /** Detects the tail of the call history repeating with period 2 or 3. */
    public boolean inCycle() {
        for (int period = 2; period <= 3; period++) {
            int n = history.size();
            if (n < period * 2) continue;
            if (history.subList(n - period, n).equals(history.subList(n - 2 * period, n - period))) {
                return true;
            }
        }
        return false;
    }

    /** Called when a repetition problem was observed this turn. */
    public Verdict escalate(String problem) {
        nudgesUsed++;
        if (nudgesUsed > maxNudges) {
            return new Verdict(Action.STOP, problem);
        }
        return new Verdict(Action.NUDGE, problem
                + " Do not repeat calls: everything you need is already in the conversation and the tool ledger."
                + " Synthesize what you have and move on, or give your final answer now.");
    }

    public Verdict checkBudget(long tokensUsed) {
        if (tokensUsed >= tokenBudget) {
            return new Verdict(Action.STOP, "Token budget of " + tokenBudget + " exhausted.");
        }
        if (!budgetWarned && tokensUsed >= tokenBudget * 0.8) {
            budgetWarned = true;
            return new Verdict(Action.NUDGE, "You have used 80% of your token budget. Finish the essential work and give your final answer.");
        }
        return Verdict.CONTINUE;
    }
}
