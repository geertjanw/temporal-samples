package com.example.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface OrderActivities {

    @ActivityMethod
    String validateOrder(OrderRequest request);

    /**
     * Security-sensitive step: requires the Shiro permission "order:approve".
     * Throws a non-retryable ApplicationFailure if the propagated principal
     * lacks the permission.
     */
    @ActivityMethod
    String approveOrder(OrderRequest request);

    @ActivityMethod
    String fulfillOrder(OrderRequest request);
}
