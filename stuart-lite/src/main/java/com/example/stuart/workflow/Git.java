package com.example.stuart.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Tiny wrapper around the git CLI. */
final class Git {

    record Result(int exitCode, String output) {
    }

    private Git() {
    }

    static String run(Path dir, String... args) {
        Result r = tryRun(dir, args);
        if (r.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed (" + r.exitCode() + "): " + r.output());
        }
        return r.output().strip();
    }

    static Result tryRun(Path dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("user.name=Stuart (AI agent)");
        cmd.add("-c");
        cmd.add("user.email=stuart@localhost");
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IllegalStateException("git " + String.join(" ", args) + " timed out");
            }
            return new Result(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot run git: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** Stage everything and commit. Returns the new sha, or null if there was nothing to commit. */
    static String commitAll(Path dir, String message) {
        run(dir, "add", "-A");
        if (tryRun(dir, "diff", "--cached", "--quiet").exitCode() == 0) {
            return null;
        }
        run(dir, "commit", "-m", message);
        return run(dir, "rev-parse", "HEAD");
    }
}
