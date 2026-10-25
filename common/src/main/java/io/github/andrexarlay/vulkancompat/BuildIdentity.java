package io.github.andrexarlay.vulkancompat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/** Immutable host build identity, read once from its unique packaged resource. No runtime Git or
 * filesystem probing: absent or invalid fields remain explicitly unknown, including dirty state. */
public final class BuildIdentity {
    private static final String UNKNOWN = "unknown";
    private static final BuildIdentity CURRENT = load();
    private final String version, commit, dirty, source, display;

    private BuildIdentity(String version, String commit, String dirty, String source) {
        this.version = version;
        this.commit = commit;
        this.dirty = dirty;
        this.source = source;
        String shortCommit = UNKNOWN.equals(commit) ? UNKNOWN : commit.substring(0, 12);
        this.display = version + " @" + shortCommit + switch (dirty) {
            case "true" -> "+dirty";
            case "false" -> "";
            default -> " dirty?";
        };
    }

    public static BuildIdentity current() { return CURRENT; }
    public String version() { return version; }
    public String commit() { return commit; }
    /** One of true, false or unknown; an unreadable build never claims to be clean. */
    public String dirty() { return dirty; }
    public String source() { return source; }
    public String display() { return display; }

    private static BuildIdentity load() {
        try (var input = BuildIdentity.class.getResourceAsStream("/META-INF/bedray-build.properties")) {
            if (input == null) return unknown();
            Properties values = new Properties();
            values.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            String version = values.getProperty("version", UNKNOWN).strip();
            if (!version.matches("[0-9A-Za-z][0-9A-Za-z._+\\-]{0,95}")) version = UNKNOWN;
            String commit = values.getProperty("commit", UNKNOWN).strip().toLowerCase(Locale.ROOT);
            if (!commit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) commit = UNKNOWN;
            String dirty = values.getProperty("dirty", UNKNOWN).strip();
            if (!dirty.equals("true") && !dirty.equals("false")) dirty = UNKNOWN;
            String source = values.getProperty("source", UNKNOWN).strip();
            if (!source.equals("git-checkout") && !source.equals("source-archive")) source = UNKNOWN;
            return new BuildIdentity(version, commit, dirty, source);
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            return unknown();
        }
    }

    private static BuildIdentity unknown() { return new BuildIdentity(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN); }
}
