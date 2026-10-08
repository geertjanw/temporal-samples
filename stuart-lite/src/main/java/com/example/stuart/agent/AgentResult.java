package com.example.stuart.agent;

public record AgentResult(
        String finalText,
        FinishReason finishReason,
        int iterations,
        int toolCalls,
        long promptTokens,
        long completionTokens) {

    public enum FinishReason {
        /** The model answered without asking for a tool. */
        COMPLETED,
        MAX_ITERATIONS,
        TOKEN_BUDGET,
        /** Repetition / cycles persisted after the nudges ran out. */
        LOOP_DETECTED
    }
}
