package io.github.andrexarlay.vulkancompat.sodium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sodium only lets an option's enabled predicate read the options it declared as dependencies; any other read throws
 * "Attempted to read option value that is not a declared dependency" while the settings screen builds its rows, and the
 * Bedray screen then shows no rows at all (26.3(1)/latest.log, 2026-10-31 01:42, the path_restir_pt_spatial row). This
 * reads every row of VulkanSodiumConfig that names its dependencies through {@code rtDependencies(...)} and checks that
 * each Identifier its predicate reads is in that list. The mode helpers (anyRt, pathOn, raysOn) read {@code modeId},
 * which rtDependencies always adds, and {@code renderer}, which it adds too.
 */
class SettingsDeclaredDependenciesTest {

    private static final Path CONFIG = Path.of(
        "src/main/java/io/github/andrexarlay/vulkancompat/sodium/VulkanSodiumConfig.java");
    private static final Pattern READ = Pattern.compile("s\\.read(?:Boolean|Enum|Int)Option\\((\\w+)");
    private static final Pattern DEPENDENCIES = Pattern.compile("rtDependencies\\(([^)]*)\\)");

    @Test
    void everyRowDeclaresEveryOptionItsPredicateReads() throws IOException {
        String source = Files.readString(CONFIG, StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<String> problems = new ArrayList<>();
        int rows = 0;
        for (int at = source.indexOf("addOption("); at >= 0; at = source.indexOf("addOption(", at + 1)) {
            String row = statement(source, at);
            Matcher declared = DEPENDENCIES.matcher(row);
            if (!declared.find()) {
                continue;   // a row without rtDependencies names its dependencies another way; not checked here
            }
            rows++;
            Set<String> names = new LinkedHashSet<>();
            for (String name : declared.group(1).split(",")) {
                names.add(name.trim());
            }
            Matcher read = READ.matcher(row);
            while (read.find()) {
                String variable = read.group(1);
                if (!names.contains(variable)) {
                    problems.add(label(row) + " reads " + variable + " but declares " + names);
                }
            }
        }
        assertTrue(rows > 10, "the scan found the rows (" + rows + ")");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** The addOption(...) statement starting at {@code at}, up to its balanced closing parenthesis. */
    private static String statement(String source, int at) {
        int depth = 0;
        for (int i = source.indexOf('(', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced addOption at " + at);
    }

    private static String label(String row) {
        Matcher key = Pattern.compile("\"([a-z0-9_]+)\"").matcher(row);
        return key.find() ? key.group(1) : row.substring(0, Math.min(60, row.length()));
    }
}
