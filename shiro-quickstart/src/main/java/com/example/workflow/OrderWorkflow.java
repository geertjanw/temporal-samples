package com.example.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface OrderWorkflow {

    String TASK_QUEUE = "ORDER_TASK_QUEUE";

    @WorkflowMethod
    String processOrder(OrderRequest request);
}
