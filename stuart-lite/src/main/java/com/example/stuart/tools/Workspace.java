package com.example.stuart.tools;

import java.nio.file.Path;

/**
 * A throwaway clone that one task works in. Every path coming from the model is resolved here, and
 * anything that escapes the repository (../, absolute paths elsewhere, UNC paths, .git) is refused.
 */
public final class Workspace {

    private final Path root;

    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** @return the resolved path, or {@code null} if it is not allowed */
    public Path resolve(String path) {
        if (path == null || path.isBlank() || path.startsWith("\\\\") || path.startsWith("//")) {
            return null;
        }
        Path p = Path.of(path);
        Path resolved = (p.isAbsolute() ? p : root.resolve(p)).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        Path relative = root.relativize(resolved);
        if (relative.getNameCount() > 0 && relative.getName(0).toString().equals(".git")) {
            return null;
        }
        return resolved;
    }

    public String relative(Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
