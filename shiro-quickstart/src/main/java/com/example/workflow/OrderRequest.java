package com.example.workflow;

/**
 * Workflow input. Note: we propagate only the *principal name* into the
 * workflow — never passwords, tokens, or Shiro sessions. Workflow inputs are
 * persisted in Temporal's event history, so they must not contain secrets.
 */
public class OrderRequest {
    private String username;
    private String orderId;
    private double amount;

    public OrderRequest() {} // required by Jackson (Temporal's default serializer)

    public OrderRequest(String username, String orderId, double amount) {
        this.username = username;
        this.orderId = orderId;
        this.amount = amount;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
}
