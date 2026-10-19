package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Raw, branch-insensitive read of a {@code .properties} file of a shaderpack (shaders.properties, dimension.properties).
 * <p>
 * These files run through a C preprocessor in Iris/OptiFine ({@code #ifdef SHADOW ... #endif}), so the effective value of a
 * key depends on pack options. I1 does NOT evaluate directives (that is I2, together with GLSL preprocessing): every value of a
 * key found in any branch is kept, in file order, and {@link #conditional()} says whether branches exist at all. Feature
 * detection on this view is therefore an upper bound ("may need"), which is the right direction for a capability report.
 */
public final class PackProperties {
    private static final Set<String> DIRECTIVES = Set.of("if", "ifdef", "ifndef", "elif", "else", "endif", "define", "undef", "include");

    private final Map<String, List<String>> values;
    private final int directiveLines;
    private final boolean conditional;

    private PackProperties(Map<String, List<String>> values, int directiveLines, boolean conditional) {
        this.values = values;
        this.directiveLines = directiveLines;
        this.conditional = conditional;
    }

    public static PackProperties empty() {
        return new PackProperties(Map.of(), 0, false);
    }

    public static PackProperties parse(String text) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        int directives = 0;
        boolean conditional = false;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder logical = new StringBuilder();
        for (String physical : lines) {
            String line = physical.strip();
            if (line.startsWith("﻿")) {
                line = line.substring(1).strip();
            }
            if (line.endsWith("\\")) {
                logical.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            logical.append(line);
            String l = logical.toString().strip();
            logical.setLength(0);
            if (l.isEmpty()) {
                continue;
            }
            if (l.startsWith("#")) {
                String word = l.substring(1).strip();
                int sp = 0;
                while (sp < word.length() && Character.isLetter(word.charAt(sp))) {
                    sp++;
                }
                String d = word.substring(0, sp);
                if (DIRECTIVES.contains(d)) {
                    directives++;
                    if (d.startsWith("if") || d.equals("elif") || d.equals("else")) {
                        conditional = true;
                    }
                }
                continue; // a comment or a directive, never a key
            }
            if (l.startsWith("!")) {
                continue;
            }
            int eq = l.indexOf('=');
            if (eq < 0) {
                eq = l.indexOf(':');
            }
            if (eq <= 0) {
                continue;
            }
            String key = l.substring(0, eq).strip();
            String val = l.substring(eq + 1).strip();
            values.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        values.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return new PackProperties(Collections.unmodifiableMap(frozen), directives, conditional);
    }

    /** All values of a key across all preprocessor branches, file order; empty list when absent. */
    public List<String> all(String key) {
        return values.getOrDefault(key, List.of());
    }

    /** Keys in file order. */
    public Set<String> keys() {
        return values.keySet();
    }

    public int directiveLines() {
        return directiveLines;
    }

    /** True when the file has #if/#ifdef/#ifndef/#elif/#else branches (values may be option-dependent). */
    public boolean conditional() {
        return conditional;
    }
}
