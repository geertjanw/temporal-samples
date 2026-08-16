package com.example.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * Workflow code must be deterministic, so NO Shiro calls happen here —
 * security checks belong at the edge (client) and inside activities.
 * The workflow just orchestrates and carries the principal name along.
 */
public class OrderWorkflowImpl implements OrderWorkflow {

    private final OrderActivities activities = Workflow.newActivityStub(
            OrderActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public String processOrder(OrderRequest request) {
        StringBuilder log = new StringBuilder();
        log.append(activities.validateOrder(request)).append(" | ");
        log.append(activities.approveOrder(request)).append(" | ");
        log.append(activities.fulfillOrder(request));
        return log.toString();
    }
}
