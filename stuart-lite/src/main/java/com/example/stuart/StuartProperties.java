package com.example.stuart;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stuart")
public record StuartProperties(
        String workspaceRoot,
        boolean gitPush,
        long tokenBudgetPerPhase,
        double inputPricePerMillion,
        double outputPricePerMillion) {

    public double estimateCost(long promptTokens, long completionTokens) {
        return promptTokens / 1_000_000.0 * inputPricePerMillion
                + completionTokens / 1_000_000.0 * outputPricePerMillion;
    }
}
