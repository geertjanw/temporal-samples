package com.example.stuart.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Mutating tools. Only implementation phases get these. All paths are bounded to the workspace. */
public class WriteTools {

    /** Tool names the guardrails treat as state-changing. */
    public static final Set<String> NAMES = Set.of("writeFile", "editFile", "deleteFile");

    private final Workspace ws;

    public WriteTools(Workspace ws) {
        this.ws = ws;
    }

    @Tool(name = "writeFile", description = """
            Creates a new file, or overwrites an existing one, with the given full content.
            - Prefer editFile for small changes to existing files.
            - Parent directories are created automatically.
            """)
    public String writeFile(
            @ToolParam(description = "Path relative to the repository root") String path,
            @ToolParam(description = "Complete file content") String content) {
        Path file = ws.resolve(path);
        if (file == null) return "Error: path is outside the repository: " + path;
        if (Files.isDirectory(file)) return "Error: path is a directory: " + path;
        try {
            Files.createDirectories(file.getParent());
            boolean existed = Files.exists(file);
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            return (existed ? "Overwrote " : "Created ") + ws.relative(file);
        } catch (IOException e) {
            return "Error: cannot write " + path + ": " + e.getMessage();
        }
    }

    @Tool(name = "editFile", description = """
            Replaces one exact occurrence of oldText with newText in a file.
            - oldText must match the file exactly (including indentation) and must be unique in the file;
              include a few surrounding lines to make it unique.
            - Read the file first so you know its exact contents.
            """)
    public String editFile(
            @ToolParam(description = "Path relative to the repository root") String path,
            @ToolParam(description = "Exact text to replace") String oldText,
            @ToolParam(description = "Replacement text") String newText) {
        Path file = ws.resolve(path);
        if (file == null) return "Error: path is outside the repository: " + path;
        if (!Files.isRegularFile(file)) return "Error: file does not exist: " + path;
        if (oldText == null || oldText.isEmpty()) return "Error: oldText must not be empty";
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int first = content.indexOf(oldText);
            if (first < 0) return "Error: oldText not found in " + path + ". Re-read the file and copy the text exactly.";
            if (content.indexOf(oldText, first + 1) >= 0) return "Error: oldText occurs more than once in " + path + ". Add more context.";
            String updated = content.substring(0, first) + (newText == null ? "" : newText) + content.substring(first + oldText.length());
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return "Edited " + ws.relative(file);
        } catch (IOException e) {
            return "Error: cannot edit " + path + ": " + e.getMessage();
        }
    }

    @Tool(name = "deleteFile", description = """
            Deletes a file from the repository. Afterwards make sure nothing still references it
            (imports, configuration, tests).
            """)
    public String deleteFile(@ToolParam(description = "Path relative to the repository root") String path) {
        Path file = ws.resolve(path);
        if (file == null) return "Error: path is outside the repository: " + path;
        if (file.equals(ws.root())) return "Error: refusing to delete the repository root";
        if (!Files.exists(file)) return "Error: file does not exist: " + path;
        if (Files.isDirectory(file)) return "Error: path is a directory, not a file: " + path;
        try {
            Files.delete(file);
            return "Deleted " + ws.relative(file);
        } catch (IOException e) {
            return "Error: cannot delete " + path + ": " + e.getMessage();
        }
    }
}
