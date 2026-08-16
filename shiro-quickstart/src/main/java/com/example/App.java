package com.example;

import com.example.security.ShiroSecurity;
import com.example.workflow.OrderActivitiesImpl;
import com.example.workflow.OrderRequest;
import com.example.workflow.OrderWorkflow;
import com.example.workflow.OrderWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.subject.Subject;

/**
 * Demo: Apache Shiro guarding Temporal workflows.
 *
 * Security model:
 *  1. EDGE:     user logs in via Shiro; starting a workflow requires "order:submit".
 *  2. TRANSIT:  only the principal NAME travels inside the workflow input.
 *  3. ACTIVITY: privileged steps re-check Shiro permissions ("order:approve").
 *
 * Two run modes:
 *  - default:                    in-process test server, no Web UI, no install needed.
 *  - -Dtemporal.mode=server:     connects to a real Temporal server on localhost:7233
 *                                (e.g. `temporal server start-dev`), so runs are
 *                                visible in the Web UI at http://localhost:8233.
 */
public class App {

    public static void main(String[] args) throws Exception {
        // ---- Shiro bootstrap ------------------------------------------------
        ShiroSecurity.init();

        boolean realServer = "server".equalsIgnoreCase(System.getProperty("temporal.mode", "test"));

        TestWorkflowEnvironment testEnv = null;
        WorkflowServiceStubs serviceStubs = null;
        WorkflowClient client;
        WorkerFactory factory = null;

        if (realServer) {
            // ---- Real Temporal server (visible in the Web UI) ----------------
            System.out.println("Connecting to Temporal server at localhost:7233 ...");
            serviceStubs = WorkflowServiceStubs.newLocalServiceStubs();
            client = WorkflowClient.newInstance(serviceStubs);
            factory = WorkerFactory.newInstance(client);
            Worker worker = factory.newWorker(OrderWorkflow.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
            worker.registerActivitiesImplementations(new OrderActivitiesImpl());
            factory.start();
        } else {
            // ---- In-process test server (no Web UI) --------------------------
            testEnv = TestWorkflowEnvironment.newInstance();
            Worker worker = testEnv.newWorker(OrderWorkflow.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
            worker.registerActivitiesImplementations(new OrderActivitiesImpl());
            testEnv.start();
            client = testEnv.getWorkflowClient();
        }

        // Unique suffix so re-runs against a real server don't collide with
        // already-completed workflow IDs from previous runs.
        String runSuffix = realServer ? "-" + System.currentTimeMillis() : "";

        // Scenario 1: alice (admin) — may submit AND approve -> succeeds.
        runScenario(client, "alice", "secret123", new OrderRequest("alice", "ORD-1001" + runSuffix, 250.0));

        // Scenario 2: bob (clerk) — may submit, but approval fails inside the
        // workflow because he lacks "order:approve".
        runScenario(client, "bob", "password123", new OrderRequest("bob", "ORD-1002" + runSuffix, 99.0));

        // Scenario 3: wrong password -> Shiro rejects at the edge, workflow never starts.
        runScenario(client, "bob", "wrong-password", new OrderRequest("bob", "ORD-1003" + runSuffix, 10.0));

        if (realServer) {
            System.out.println("\nDone. Inspect the runs in the Web UI: http://localhost:8233");
            factory.shutdown();
            factory.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            serviceStubs.shutdown();
        } else {
            testEnv.close();
        }
    }

    private static void runScenario(WorkflowClient client, String username,
                                    String password, OrderRequest request) {
        System.out.println("\n=== Scenario: user=" + username + ", order=" + request.getOrderId() + " ===");

        // 1) Authenticate at the edge with Shiro.
        Subject subject;
        try {
            subject = ShiroSecurity.login(username, password);
        } catch (AuthenticationException e) {
            System.out.println("LOGIN FAILED: " + e.getMessage() + " -> workflow not started.");
            return;
        }

        try {
            // 2) Authorize workflow start.
            if (!subject.isPermitted("order:submit")) {
                System.out.println("DENIED: '" + username + "' may not submit orders.");
                return;
            }

            // 3) Start the Temporal workflow, propagating only the principal name.
            OrderWorkflow workflow = client.newWorkflowStub(
                    OrderWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue(OrderWorkflow.TASK_QUEUE)
                            .setWorkflowId("order-" + request.getOrderId())
                            .build());
            try {
                String result = workflow.processOrder(request);
                System.out.println("WORKFLOW OK: " + result);
            } catch (WorkflowException e) {
                // e.g. the AccessDenied ApplicationFailure from approveOrder
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                System.out.println("WORKFLOW FAILED: " + root.getMessage());
            }
        } finally {
            subject.logout();
        }
    }
}
