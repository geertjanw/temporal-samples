package com.example.workflow;

import com.example.security.ShiroSecurity;
import io.temporal.failure.ApplicationFailure;

/**
 * Activities run on worker threads, so we cannot rely on the thread-bound
 * Subject created during login on the client. Instead we re-hydrate a Subject
 * from the username carried in the workflow input and enforce Shiro
 * permissions here — the point where side effects actually happen.
 */
public class OrderActivitiesImpl implements OrderActivities {

    @Override
    public String validateOrder(OrderRequest request) {
        if (request.getAmount() <= 0) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Order amount must be positive", "ValidationError");
        }
        return "Order %s validated (amount=%.2f)"
                .formatted(request.getOrderId(), request.getAmount());
    }

    @Override
    public String approveOrder(OrderRequest request) {
        String user = request.getUsername();

        // Shiro authorization check inside a Temporal activity.
        if (!ShiroSecurity.isPermitted(user, "order:approve")) {
            // Non-retryable: retrying will not change the user's permissions.
            throw ApplicationFailure.newNonRetryableFailure(
                    "User '%s' lacks permission 'order:approve'".formatted(user),
                    "AccessDenied");
        }
        return "Order %s approved by %s".formatted(request.getOrderId(), user);
    }

    @Override
    public String fulfillOrder(OrderRequest request) {
        return "Order %s fulfilled".formatted(request.getOrderId());
    }
}
