package com.example.fraud;

/*
 * FraudDetectionApp.java
 * ----------------------
 * Temporal.io + Deep Netts (https://github.com/deepnetts/CreditCardFraudDetection)
 * in a single source file.
 *
 * Contains:
 *   1. TrainingWorkflow  - durably trains the Deep Netts fraud model from the CSV
 *   2. ScreeningWorkflow - scores a transaction; if suspicious, waits (up to 48h)
 *                          for a human review signal before deciding
 *   3. Activities        - all Deep Netts calls live here (non-deterministic code
 *                          must never run inside workflow code)
 *   4. main()            - starts a worker, trains the model, screens a sample
 *                          transaction, and demonstrates the review signal
 *
 * Prerequisites:
 *   - Temporal dev server running:  temporal server start-dev
 *   - creditcard-balanced.csv from the repo in the working directory
 *
 * Maven dependencies (add to the repo's pom.xml):
 *
 *   <dependency>
 *     <groupId>io.temporal</groupId>
 *     <artifactId>temporal-sdk</artifactId>
 *     <version>1.25.2</version>
 *   </dependency>
 *   <dependency>
 *     <groupId>com.deepnetts</groupId>
 *     <artifactId>deepnetts-core</artifactId>
 *     <version>1.13.2</version>
 *   </dependency>
 */

import deepnetts.data.DataSets;
import deepnetts.eval.Evaluators;
import deepnetts.net.FeedForwardNetwork;
import deepnetts.net.layers.activation.ActivationType;
import deepnetts.net.loss.LossType;
import deepnetts.net.train.BackpropagationTrainer;
import deepnetts.util.FileIO;
import deepnetts.util.Tensor;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import javax.visrec.ml.data.DataSet;
import javax.visrec.ml.eval.EvaluationMetrics;
import java.io.BufferedReader;
import java.io.FileReader;
import java.time.Duration;

public class FraudDetectionApp {

    static final String TASK_QUEUE = "FRAUD_TASK_QUEUE";
    static final String CSV_FILE   = "creditcard-balanced.csv";
    static final String MODEL_FILE = "fraudDetectionModel.dnet";

    /** Per-column max-abs scaling factors, saved next to the model. */
    static final String SCALER_FILE = "fraudDetectionScaler.bin";

    /** Number of feature columns in the CSV (all columns except the class label).
     *  Adjust if your CSV differs. */
    static final int NUM_INPUTS = 29;

    /** Fraud probability above which a transaction goes to human review. */
    static final float FRAUD_THRESHOLD = 0.5f;

    // ------------------------------------------------------------------
    // 1. Workflow interfaces
    // ------------------------------------------------------------------

    @WorkflowInterface
    public interface TrainingWorkflow {
        @WorkflowMethod
        String trainModel(String csvPath);
    }

    @WorkflowInterface
    public interface ScreeningWorkflow {
        @WorkflowMethod
        String screen(float[] transactionFeatures);

        /** Sent by a human reviewer for flagged transactions. */
        @SignalMethod
        void reviewDecision(boolean approved);

        /** Read-only view of the case, callable while the workflow runs —
         *  from the CLI, the Web UI's Queries tab, or any client. */
        @QueryMethod
        String caseStatus();
    }

    // ------------------------------------------------------------------
    // 2. Activity interface — all Deep Netts / IO work happens here
    // ------------------------------------------------------------------

    @ActivityInterface
    public interface FraudActivities {
        @ActivityMethod
        String trainAndSaveModel(String csvPath);

        @ActivityMethod
        float predictFraudProbability(float[] features);

        @ActivityMethod
        void notifyFraudTeam(String workflowId, float score);

        @ActivityMethod
        void recordDecision(String decision);
    }

    // ------------------------------------------------------------------
    // 3. Workflow implementations (deterministic orchestration only)
    // ------------------------------------------------------------------

    public static class TrainingWorkflowImpl implements TrainingWorkflow {

        private final FraudActivities activities = Workflow.newActivityStub(
                FraudActivities.class,
                ActivityOptions.newBuilder()
                        // training may take a while
                        .setStartToCloseTimeout(Duration.ofMinutes(30))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setMaximumAttempts(3)
                                .build())
                        .build());

        @Override
        public String trainModel(String csvPath) {
            // If the worker crashes mid-training, Temporal retries automatically.
            return activities.trainAndSaveModel(csvPath);
        }
    }

    public static class ScreeningWorkflowImpl implements ScreeningWorkflow {

        private final FraudActivities activities = Workflow.newActivityStub(
                FraudActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofMinutes(1))
                        .build());

        private Boolean humanApproved = null; // set by signal

        // Case state exposed via the caseStatus query
        private float fraudScore = -1f;
        private String status = "SCORING";

        @Override
        public String screen(float[] features) {
            fraudScore = activities.predictFraudProbability(features);

            if (fraudScore < FRAUD_THRESHOLD) {
                status = "AUTO_APPROVED";
                activities.recordDecision("AUTO-APPROVED (score=" + fraudScore + ")");
                return "APPROVED automatically, fraud score " + fraudScore;
            }

            // Suspicious: notify fraud team and durably wait for a human.
            status = "AWAITING_REVIEW";
            activities.notifyFraudTeam(Workflow.getInfo().getWorkflowId(), fraudScore);

            // The workflow "sleeps" here at zero cost — survives restarts,
            // deployments and crashes — until a signal arrives or 48h pass.
            boolean signaled = Workflow.await(Duration.ofHours(48),
                    () -> humanApproved != null);

            String decision;
            if (!signaled) {
                status = "BLOCKED_TIMEOUT";
                decision = "BLOCKED (review timed out after 48h), score " + fraudScore;
            } else if (humanApproved) {
                status = "APPROVED_BY_REVIEWER";
                decision = "APPROVED by human reviewer, score " + fraudScore;
            } else {
                status = "BLOCKED_BY_REVIEWER";
                decision = "BLOCKED by human reviewer, score " + fraudScore;
            }
            activities.recordDecision(decision);
            return decision;
        }

        @Override
        public void reviewDecision(boolean approved) {
            this.humanApproved = approved;
        }

        @Override
        public String caseStatus() {
            return String.format("status=%s, fraudScore=%s, threshold=%s",
                    status, fraudScore < 0 ? "n/a" : fraudScore, FRAUD_THRESHOLD);
        }
    }

    // ------------------------------------------------------------------
    // 4. Activity implementation — the Deep Netts code from the repo
    // ------------------------------------------------------------------

    public static class FraudActivitiesImpl implements FraudActivities {

        /** In-memory cache: prediction in the same worker process never
         *  depends on the file on disk. */
        private static volatile FeedForwardNetwork cachedModel;
        private static volatile float[] cachedScaler;

        @Override
        public String trainAndSaveModel(String csvPath) {
            String resolved = resolveCsv(csvPath);
            try {
                // Max-abs scaling: without it, the large Time/Amount columns
                // saturate the TANH layer and the network collapses to
                // predicting a single class (TruePositive = 0).
                System.out.println("Computing feature scaling from " + resolved + " ...");
                float[] maxAbs = computeMaxAbs(resolved);
                String scaledCsv = writeScaledCsv(resolved, maxAbs);
                saveScaler(maxAbs);
                cachedScaler = maxAbs;

                System.out.println("Loading dataset " + scaledCsv + " ...");
                DataSet dataSet = DataSets.readCsv(scaledCsv, NUM_INPUTS, 1, true);

                DataSet[] split = dataSet.split(0.7, 0.3);
                DataSet trainSet = split[0];
                DataSet testSet  = split[1];

                // Vary the random seed per Temporal retry attempt: if training
                // diverges (NaN), the retry explores a different weight init
                // instead of deterministically failing the same way.
                int attempt = io.temporal.activity.Activity
                        .getExecutionContext().getInfo().getAttempt();

                // Same architecture as the Deep Netts example
                FeedForwardNetwork neuralNet = FeedForwardNetwork.builder()
                        .addInputLayer(NUM_INPUTS)
                        .addFullyConnectedLayer(16, ActivationType.TANH)
                        .addOutputLayer(1, ActivationType.SIGMOID)
                        .lossFunction(LossType.CROSS_ENTROPY)
                        .randomSeed(123 + attempt)
                        .build();

                BackpropagationTrainer trainer = neuralNet.getTrainer();
                // lr 0.01 drove the sigmoid outputs into saturation and
                // cross-entropy into log(0) => NaN at ~epoch 88. A smaller
                // step and an achievable target error stop well before that.
                trainer.setLearningRate(0.001f);
                trainer.setMaxError(0.08f);
                trainer.setMaxEpochs(5000);

                System.out.println("Training fraud detection model (attempt "
                        + attempt + ") ...");
                neuralNet.train(trainSet);

                // Divergence guard: a NaN-poisoned network must never be
                // saved. Throwing here makes Temporal retry training with
                // the next seed.
                neuralNet.setInput(new Tensor(new float[NUM_INPUTS]));
                if (Float.isNaN(neuralNet.getOutput()[0])) {
                    throw new RuntimeException(
                            "Training diverged (NaN weights) — retrying with a new seed");
                }

                EvaluationMetrics metrics =
                        Evaluators.evaluateClassifier(neuralNet, testSet);

                // Atomic save: write to a temp file first, then move into
                // place, so a crash mid-write can never leave a corrupt
                // half-written model behind.
                java.io.File tmp = new java.io.File(MODEL_FILE + ".tmp");
                FileIO.writeToFile(neuralNet, tmp.getPath());
                moveIntoPlace(tmp, new java.io.File(MODEL_FILE));
                cachedModel = neuralNet;
                System.out.println("Model saved to " + MODEL_FILE);

                return "Training complete. Evaluation: " + metrics.toString();
            } catch (Exception e) {
                // Throwing lets Temporal apply the retry policy
                throw new RuntimeException("Training failed: " + e.getMessage(), e);
            }
        }

        @Override
        public float predictFraudProbability(float[] features) {
            FeedForwardNetwork neuralNet = cachedModel;
            if (neuralNet == null) {
                try {
                    neuralNet = FileIO.createFromFile(MODEL_FILE, FeedForwardNetwork.class);
                    cachedModel = neuralNet;
                } catch (Exception e) {
                    // Deep Netts binary serialization has proven unreliable
                    // across JVMs ("invalid type code"). Training only takes
                    // ~100ms on this dataset, so instead of failing the
                    // screening, retrain from the CSV right here.
                    System.out.println("[MODEL] Could not load " + MODEL_FILE
                            + " (" + e.getMessage()
                            + ") — retraining from CSV instead ...");
                    trainAndSaveModel(CSV_FILE); // repopulates cachedModel + scaler
                    neuralNet = cachedModel;
                }
            }
            try {
                float[] maxAbs = cachedScaler;
                if (maxAbs == null) {
                    maxAbs = loadScaler();
                    cachedScaler = maxAbs;
                }
                float[] scaled = new float[features.length];
                for (int i = 0; i < features.length; i++) {
                    scaled[i] = maxAbs[i] == 0f ? 0f : features[i] / maxAbs[i];
                }
                neuralNet.setInput(new Tensor(scaled));
                float score = neuralNet.getOutput()[0];
                if (Float.isNaN(score)) {
                    // Clear the poisoned cache AND the file so the retry
                    // retrains instead of reloading the same diverged model.
                    cachedModel = null;
                    new java.io.File(MODEL_FILE).delete();
                    throw new RuntimeException(
                            "Model produced NaN score — cleared, retry will retrain");
                }
                return score;
            } catch (Exception e) {
                throw new RuntimeException("Prediction failed: " + e.getMessage(), e);
            }
        }

        // ---- scaling helpers (plain-Java, persisted next to the model) ----

        /** Max of |value| per feature column, computed from the CSV. */
        private static float[] computeMaxAbs(String csvPath) throws Exception {
            float[] maxAbs = new float[NUM_INPUTS];
            try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
                br.readLine(); // header
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] cols = line.split(",");
                    for (int i = 0; i < NUM_INPUTS; i++) {
                        maxAbs[i] = Math.max(maxAbs[i],
                                Math.abs(Float.parseFloat(cols[i])));
                    }
                }
            }
            return maxAbs;
        }

        /** Writes a copy of the CSV with every feature divided by its
         *  column max-abs (label column left untouched). */
        private static String writeScaledCsv(String csvPath, float[] maxAbs)
                throws Exception {
            java.io.File out = new java.io.File(
                    new java.io.File(csvPath).getParentFile(),
                    "creditcard-balanced-scaled.csv");
            try (BufferedReader br = new BufferedReader(new FileReader(csvPath));
                 java.io.PrintWriter pw = new java.io.PrintWriter(out)) {
                pw.println(br.readLine()); // header unchanged
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] cols = line.split(",");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < NUM_INPUTS; i++) {
                        float v = Float.parseFloat(cols[i]);
                        sb.append(maxAbs[i] == 0f ? 0f : v / maxAbs[i]).append(',');
                    }
                    sb.append(cols[NUM_INPUTS]); // class label
                    pw.println(sb);
                }
            }
            return out.getAbsolutePath();
        }

        /** Atomic move where the platform allows it; falls back to a plain
         *  replace on OSes (e.g. macOS) where atomic-replace returns EPERM.
         *  A torn file is tolerable because loading falls back to retraining. */
        private static void moveIntoPlace(java.io.File tmp, java.io.File target)
                throws Exception {
            try {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                target.delete();
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static void saveScaler(float[] maxAbs) throws Exception {
            java.io.File tmp = new java.io.File(SCALER_FILE + ".tmp");
            try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                    new java.io.FileOutputStream(tmp))) {
                dos.writeInt(maxAbs.length);
                for (float v : maxAbs) dos.writeFloat(v);
            }
            moveIntoPlace(tmp, new java.io.File(SCALER_FILE));
        }

        private static float[] loadScaler() {
            try (java.io.DataInputStream dis = new java.io.DataInputStream(
                    new java.io.FileInputStream(SCALER_FILE))) {
                float[] maxAbs = new float[dis.readInt()];
                for (int i = 0; i < maxAbs.length; i++) maxAbs[i] = dis.readFloat();
                return maxAbs;
            } catch (Exception e) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "Could not load scaler '" + SCALER_FILE + "' ("
                                + e.getMessage() + "). Delete the model and "
                                + "scaler files and rerun training.",
                        "CorruptScalerFile");
            }
        }

        @Override
        public void notifyFraudTeam(String workflowId, float score) {
            // In real life: email / Slack / case-management API call.
            System.out.printf(
                    "[FRAUD TEAM] Transaction flagged (score=%.3f). " +
                    "Review it by signaling workflow '%s'.%n", score, workflowId);
        }

        @Override
        public void recordDecision(String decision) {
            // In real life: write to a database / ledger.
            System.out.println("[AUDIT LOG] " + decision);
        }
    }

    /**
     * Resolves the CSV file by checking the given path, the working
     * directory and its parent (covers running from an IDE module dir).
     * A missing file is a configuration problem that retries can never
     * fix, so it is thrown as a NON-retryable failure with a clear message.
     */
    static String resolveCsv(String csvPath) {
        java.io.File[] candidates = {
                new java.io.File(csvPath),
                new java.io.File(System.getProperty("user.dir"), csvPath),
                new java.io.File(new java.io.File(System.getProperty("user.dir"))
                        .getParentFile(), csvPath)
        };
        for (java.io.File f : candidates) {
            if (f.exists()) {
                return f.getAbsolutePath();
            }
        }
        throw ApplicationFailure.newNonRetryableFailure(
                "Dataset not found: '" + csvPath + "'. Working directory is '"
                        + System.getProperty("user.dir")
                        + "'. Copy creditcard-balanced.csv next to pom.xml "
                        + "(or unzip creditcard.zip from the original repo there).",
                "DatasetNotFound");
    }

    // ------------------------------------------------------------------
    // 5. main(): real Temporal operations, no simulation
    //
    //    worker                 run the worker (keep running in a terminal)
    //    train                  run the training workflow
    //    screen <rowNr>         screen CSV data row <rowNr> (starts a
    //                           workflow that really waits for review)
    //    pending                explain where to see waiting workflows
    //    approve <workflowId>   human reviewer approves
    //    reject  <workflowId>   human reviewer rejects
    //    result  <workflowId>   wait for and print the final decision
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "help";

        // Connect to local Temporal dev server (localhost:7233)
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);

        switch (cmd) {

            case "worker": {
                WorkerFactory factory = WorkerFactory.newInstance(client);
                Worker worker = factory.newWorker(TASK_QUEUE);
                worker.registerWorkflowImplementationTypes(
                        TrainingWorkflowImpl.class, ScreeningWorkflowImpl.class);
                worker.registerActivitiesImplementations(new FraudActivitiesImpl());
                factory.start();
                System.out.println("Worker running on task queue '" + TASK_QUEUE
                        + "'. Press Ctrl+C to stop.");
                // Keep the worker alive
                Thread.currentThread().join();
                break;
            }

            case "train": {
                TrainingWorkflow training = client.newWorkflowStub(
                        TrainingWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue(TASK_QUEUE)
                                .setWorkflowId("fraud-model-training")
                                .build());
                System.out.println(training.trainModel(CSV_FILE));
                System.exit(0);
            }

            case "screen": {
                int rowNr = args.length > 1 ? Integer.parseInt(args[1]) : 1;
                float[] tx = readTransaction(CSV_FILE, rowNr);
                int label = readLabel(CSV_FILE, rowNr);
                String workflowId = "txn-screening-row-" + rowNr;

                System.out.println("Ground truth for row " + rowNr + ": "
                        + (label == 1 ? "FRAUD" : "LEGITIMATE")
                        + " (tip: use the 'frauds' command to list fraud rows)");

                ScreeningWorkflow screening = client.newWorkflowStub(
                        ScreeningWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue(TASK_QUEUE)
                                .setWorkflowId(workflowId)
                                .build());

                // Start asynchronously: the workflow now lives on the server.
                WorkflowClient.start(screening::screen, tx);
                System.out.println("Screening workflow started: " + workflowId);
                System.out.println("If it gets flagged, approve or reject it with:");
                System.out.println("  mvn compile exec:java -Dexec.args=\"approve "
                        + workflowId + "\"");
                System.out.println("  mvn compile exec:java -Dexec.args=\"reject "
                        + workflowId + "\"");
                System.out.println("Or in the Temporal Web UI: http://localhost:8233");
                System.out.println("Fetch the decision with:");
                System.out.println("  mvn compile exec:java -Dexec.args=\"result "
                        + workflowId + "\"");
                System.exit(0);
            }

            case "approve":
            case "reject": {
                requireWorkflowId(args, cmd);
                ScreeningWorkflow screening =
                        client.newWorkflowStub(ScreeningWorkflow.class, args[1]);
                screening.reviewDecision("approve".equals(cmd));
                System.out.println("Sent '" + cmd + "' signal to workflow " + args[1]);
                System.exit(0);
            }

            case "result": {
                requireWorkflowId(args, cmd);
                io.temporal.client.WorkflowStub stub =
                        client.newUntypedWorkflowStub(args[1]);
                System.out.println("Waiting for decision of " + args[1] + " ...");
                System.out.println("Result: " + stub.getResult(String.class));
                System.exit(0);
            }

            case "frauds": {
                System.out.println("Rows labeled as FRAUD in " + CSV_FILE + ":");
                java.util.List<Integer> fraudRows = new java.util.ArrayList<>();
                try (BufferedReader br = new BufferedReader(
                        new FileReader(resolveCsv(CSV_FILE)))) {
                    br.readLine(); // header
                    String line;
                    int rowNr = 0;
                    while ((line = br.readLine()) != null) {
                        rowNr++;
                        if (line.isBlank()) continue;
                        String[] cols = line.split(",");
                        if (Float.parseFloat(cols[NUM_INPUTS]) == 1f) {
                            fraudRows.add(rowNr);
                        }
                    }
                }
                System.out.println("Total: " + fraudRows.size() + " fraud rows");
                int show = Math.min(20, fraudRows.size());
                System.out.println("First " + show + ": " + fraudRows.subList(0, show));
                if (!fraudRows.isEmpty()) {
                    System.out.println("Try: mvn compile exec:java -Dexec.args=\"screen "
                            + fraudRows.get(0) + "\"");
                }
                System.exit(0);
            }

            case "pending": {
                System.out.println("Screening cases awaiting review:");
                final boolean[] any = {false};
                client.listExecutions(
                        "ExecutionStatus='Running' AND WorkflowType='ScreeningWorkflow'")
                        .forEach(md -> {
                            any[0] = true;
                            String id = md.getExecution().getWorkflowId();
                            String caseStatus;
                            try {
                                caseStatus = client.newUntypedWorkflowStub(id)
                                        .query("caseStatus", String.class);
                            } catch (Exception e) {
                                caseStatus = "(query failed: " + e.getMessage() + ")";
                            }
                            System.out.println("  " + id + "  ->  " + caseStatus);
                        });
                if (!any[0]) {
                    System.out.println("  (none)");
                }
                System.out.println();
                System.out.println("Decide a case with:");
                System.out.println("  mvn compile exec:java -Dexec.args=\"approve <workflowId>\"");
                System.out.println("  mvn compile exec:java -Dexec.args=\"reject <workflowId>\"");
                System.out.println("Or in the Web UI (http://localhost:8233): open the "
                        + "workflow, use the Queries tab to run caseStatus, and "
                        + "Send a Signal 'reviewDecision' with input true/false.");
                System.exit(0);
            }

            default: {
                System.out.println("Usage: <command> [args]");
                System.out.println("  worker                  run the worker (keep running)");
                System.out.println("  train                   train the fraud model");
                System.out.println("  screen <rowNr>          screen CSV data row <rowNr>");
                System.out.println("  frauds                  list row numbers of fraud transactions");
                System.out.println("  approve <workflowId>    approve a flagged transaction");
                System.out.println("  reject <workflowId>     reject a flagged transaction");
                System.out.println("  result <workflowId>     wait for the final decision");
                System.out.println("  pending                 list cases awaiting review (with scores)");
                System.exit(cmd.equals("help") ? 0 : 1);
            }
        }
    }

    private static void requireWorkflowId(String[] args, String cmd) {
        if (args.length < 2) {
            System.err.println("Usage: " + cmd + " <workflowId>");
            System.exit(1);
        }
    }

    /** Reads the class label (last column) of data row {@code rowNr}. */
    private static int readLabel(String csvPath, int rowNr) throws Exception {
        try (BufferedReader br = new BufferedReader(new FileReader(resolveCsv(csvPath)))) {
            br.readLine(); // header
            String line = null;
            for (int i = 0; i < rowNr; i++) {
                line = br.readLine();
            }
            return (int) Float.parseFloat(line.split(",")[NUM_INPUTS]);
        }
    }

    /** Reads data row {@code rowNr} (1-based) of the CSV and returns its
     *  feature columns. */
    private static float[] readTransaction(String csvPath, int rowNr) throws Exception {
        try (BufferedReader br = new BufferedReader(new FileReader(resolveCsv(csvPath)))) {
            br.readLine();                       // skip header
            String line = null;
            for (int i = 0; i < rowNr; i++) {
                line = br.readLine();
                if (line == null) {
                    throw new IllegalArgumentException(
                            "CSV has fewer than " + rowNr + " data rows");
                }
            }
            String[] cols = line.split(",");
            float[] features = new float[NUM_INPUTS];
            for (int i = 0; i < NUM_INPUTS; i++) {
                features[i] = Float.parseFloat(cols[i]);
            }
            return features;                     // last column (label) dropped
        }
    }
}