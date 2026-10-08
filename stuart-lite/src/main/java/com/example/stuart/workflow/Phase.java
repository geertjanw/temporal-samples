package com.example.stuart.workflow;

/**
 * Each phase is a separate agent run with its own context, prompt and tool set.
 * Read-only phases get no write tools at all, so "don't change code while planning" is enforced by
 * the harness rather than by asking nicely.
 */
public enum Phase {

    PLAN(false, 30, """
            You are in the PLAN phase. Explore the repository and produce a concise, concrete implementation plan.
            - You cannot modify files in this phase.
            - Start from code anchors (e.g. the task key in comments) and project docs, then follow the code.
            - Output a markdown plan: files to change/create, the change in each, and edge cases.
            - If the task is ambiguous or contradicts the code so that you cannot plan safely, answer with a
              first line of exactly "CLARIFICATION NEEDED" followed by a numbered list of specific questions.
            """),

    IMPLEMENT(true, 60, """
            You are in the IMPLEMENT phase. Implement the plan you are given, following the project's conventions.
            - Change production code only; tests are handled in a later phase.
            - Make minimal, focused changes. Read before you edit.
            - When done, answer with a short summary of what you changed (file by file).
            """),

    PLAN_TESTS(false, 20, """
            You are in the PLAN_TESTS phase. Look at the changes described below and at the existing tests.
            - You cannot modify files in this phase.
            - Output a short markdown list of test cases to add or update, with the target test file for each.
            """),

    WRITE_TESTS(true, 40, """
            You are in the WRITE_TESTS phase. Write the tests from the test plan, mirroring the style of existing tests.
            - Do not change production code unless a test reveals a real bug; if so, say so explicitly.
            - When done, answer with a short summary of the tests you added.
            """),

    ADDRESS_REVIEW(true, 30, """
            You are addressing a code review comment on your merge request.
            - If the comment requests a change, make it. If you disagree, explain why politely instead.
            - Your final answer is posted as your reply in the review thread: keep it short and specific.
            """);

    private final boolean writes;
    private final int maxIterations;
    private final String instructions;

    Phase(boolean writes, int maxIterations, String instructions) {
        this.writes = writes;
        this.maxIterations = maxIterations;
        this.instructions = instructions;
    }

    public boolean writes() {
        return writes;
    }

    public int maxIterations() {
        return maxIterations;
    }

    public String instructions() {
        return instructions;
    }
}
