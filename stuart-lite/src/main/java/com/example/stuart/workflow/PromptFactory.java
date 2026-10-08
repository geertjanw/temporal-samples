package com.example.stuart.workflow;

import com.example.stuart.tools.Workspace;
import com.example.stuart.workflow.Model.PhaseRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds prompts. The system prompt is stable per phase + repo (good for prefix caching) and embeds the
 * project's own docs: team-owned, reviewable "memory" instead of whatever the model happens to know.
 */
@Component
class PromptFactory {

    /** Files a team can keep in the repo to teach the agent its conventions. */
    private static final List<String> PROJECT_DOCS = List.of("AGENTS.md", "STUART.md", "docs/agent/conventions.md");
    private static final int MAX_DOC_CHARS = 20_000;

    String system(Phase phase, Workspace ws) {
        StringBuilder sb = new StringBuilder("""
                You are Stuart, an AI software engineer on the team. You work inside a fresh git clone of the
                repository using only the tools provided. Paths are relative to the repository root.
                Be economical: search (grep/listFiles) before reading, and never re-read what you already have.
                Code anchors: comments containing a task or spec key (e.g. [PROJ-123]) mark entry points; grep for them first.

                """).append(phase.instructions());

        for (String doc : PROJECT_DOCS) {
            Path p = ws.root().resolve(doc);
            if (Files.isRegularFile(p)) {
                try {
                    String content = Files.readString(p);
                    if (content.length() > MAX_DOC_CHARS) content = content.substring(0, MAX_DOC_CHARS) + "\n[truncated]";
                    sb.append("\n\n<project-doc path=\"").append(doc).append("\">\n").append(content).append("\n</project-doc>");
                } catch (IOException ignored) {
                    // docs are optional
                }
            }
        }
        return sb.toString();
    }

    String user(PhaseRequest req) {
        var task = req.task();
        StringBuilder sb = new StringBuilder()
                .append("# Task ").append(task.key()).append(": ").append(task.title()).append("\n\n")
                .append(task.description() == null ? "" : task.description()).append('\n');
        if (task.labels() != null && !task.labels().isEmpty()) {
            sb.append("\nLabels: ").append(String.join(", ", task.labels())).append('\n');
        }
        if (req.plan() != null && !req.plan().isBlank()) {
            sb.append("\n## Implementation plan\n").append(req.plan()).append('\n');
        }
        if (req.instruction() != null && !req.instruction().isBlank()) {
            sb.append("\n## Additional input\n").append(req.instruction()).append('\n');
        }
        return sb.toString();
    }
}
