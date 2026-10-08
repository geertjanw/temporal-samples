package com.example.stuart.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** Read-only tools. Planning phases get only these, so they physically cannot change code. */
public class ReadTools {

    private static final int MAX_LINES = 400;
    private static final int MAX_FILES = 200;
    private static final int MAX_MATCHES = 100;

    private final Workspace ws;

    public ReadTools(Workspace ws) {
        this.ws = ws;
    }

    @Tool(name = "readFile", description = """
            Reads a text file from the repository and returns it with 1-based line numbers.
            - Path is relative to the repository root (e.g. src/main/java/Foo.java).
            - Long files are returned in windows of at most 400 lines; use offset to page.
            - Read a file once and work from the result; do not re-read files you already have.
            """)
    public String readFile(
            @ToolParam(description = "Path relative to the repository root") String path,
            @ToolParam(description = "1-based line to start from (default 1)", required = false) Integer offset) {
        Path file = ws.resolve(path);
        if (file == null) return "Error: path is outside the repository: " + path;
        if (!Files.isRegularFile(file)) return "Error: file does not exist: " + path;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = Math.max(1, offset == null ? 1 : offset);
            int end = Math.min(lines.size(), start + MAX_LINES - 1);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i <= end; i++) {
                sb.append(i).append(": ").append(lines.get(i - 1)).append('\n');
            }
            if (end < lines.size()) {
                sb.append("... ").append(lines.size() - end).append(" more lines; call again with offset=").append(end + 1);
            }
            return sb.isEmpty() ? "(empty file)" : sb.toString();
        } catch (IOException e) {
            return "Error: cannot read " + path + ": " + e.getMessage();
        }
    }

    @Tool(name = "listFiles", description = """
            Lists repository files matching a glob, relative to the repository root.
            Examples: "**/*.java", "src/main/resources/**", "**/*Controller*.java". Max 200 results.
            """)
    public String listFiles(@ToolParam(description = "Glob pattern, e.g. **/*.java") String glob) {
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (IllegalArgumentException e) {
            return "Error: invalid glob: " + e.getMessage();
        }
        try (Stream<Path> files = walk()) {
            List<String> hits = files.map(ws::relative)
                    .filter(rel -> matcher.matches(Path.of(rel)))
                    .sorted()
                    .limit(MAX_FILES + 1L)
                    .toList();
            if (hits.isEmpty()) return "No files match " + glob;
            String out = String.join("\n", hits.subList(0, Math.min(hits.size(), MAX_FILES)));
            return hits.size() > MAX_FILES ? out + "\n... more results, narrow the glob" : out;
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "grep", description = """
            Searches file contents with a Java regular expression and returns path:line: text matches (max 100).
            Use this first to find entry points, e.g. code anchors like [PROJ-123] that link code to specs.
            """)
    public String grep(
            @ToolParam(description = "Java regular expression") String regex,
            @ToolParam(description = "Optional glob to restrict files, e.g. **/*.java", required = false) String glob) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return "Error: invalid regex: " + e.getDescription();
        }
        PathMatcher matcher = glob == null || glob.isBlank() ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        StringBuilder sb = new StringBuilder();
        int matches = 0;
        try (Stream<Path> files = walk()) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String rel = ws.relative(file);
                if (matcher != null && !matcher.matches(Path.of(rel))) continue;
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException binaryOrUnreadable) {
                    continue;
                }
                for (int i = 0; i < lines.size(); i++) {
                    if (pattern.matcher(lines.get(i)).find()) {
                        sb.append(rel).append(':').append(i + 1).append(": ").append(lines.get(i).strip()).append('\n');
                        if (++matches >= MAX_MATCHES) {
                            return sb.append("... stopped at ").append(MAX_MATCHES).append(" matches, refine the search").toString();
                        }
                    }
                }
            }
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
        return matches == 0 ? "No matches for " + regex : sb.toString();
    }

    private Stream<Path> walk() throws IOException {
        Path git = ws.root().resolve(".git");
        return Files.walk(ws.root())
                .filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(git))
                .filter(p -> !ws.relative(p).contains("/target/") && !ws.relative(p).startsWith("target/")
                        && !ws.relative(p).contains("node_modules/"));
    }
}
