package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisPropertiesPreprocessor;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisSourcePreprocessor;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackProperties;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackPaths;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackScanException;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable source/options snapshot for one graph build. Settings are read-only and never written back to Iris.
 * Option/profile format rules are adapted from Iris (LGPL-3.0, Iris contributors); see THIRD_PARTY_NOTICES.md.
 * This conservative post dialect rejects requested ambiguous options and unsupported value syntax explicitly.
 */
public final class IrisPackOptions {
    public static final String PROFILE_PROPERTY = "bedray.iris.profile";
    public static final Map<String, String> ENVIRONMENT = Map.of("BEDRAY_VULKAN", "1", "BEDRAY_IRIS_POST", "1");
    private static final String NAME = "[A-Za-z_]\\w*";
    private static final String TOKEN = "(?:[A-Za-z_]\\w*|[+-]?(?:0[xX][0-9A-Fa-f]+[uU]?|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fFuU]?))";
    private static final Pattern DEFINE = Pattern.compile("^\\s*(//\\s*)?#define\\s+(" + NAME + ")(?:\\s+(.*))?$" );
    private static final Pattern CONSTANT = Pattern.compile("^\\s*const\\s+(bool|int|float)\\s+(" + NAME + ")\\s*=\\s*(" + TOKEN + ")\\s*;\\s*(?://(.*))?$" );
    private static final Pattern REFERENCE = Pattern.compile("^\\s*#(?:ifdef|ifndef)\\s+(" + NAME + ")\\s*$");
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#\\s*include\\s+[\"<]([^\">]+)[\">]");
    private static final Pattern VALUES = Pattern.compile("\\[[^]\\r\\n]*]");
    private static final Pattern PROGRAM = Pattern.compile("(?:composite(?:[0-9]+)?(?:_[a-z])?|final(?:_[a-z])?)");
    private static final int MAX_OPTIONS = 4096, MAX_PROFILES = 256, MAX_PROFILE_TOKENS = 16384;
    private static final Set<String> CONST_NAMES = constNames();

    public record Request(Map<String, String> overrides, String profile) {
        public Request { overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides)); }
        public static Request defaults() { return new Request(Map.of(), null); }
    }

    public record Summary(int changedOptions, String matchedProfile, Set<String> disabledPrograms, List<String> diagnostics) {
        public Summary { disabledPrograms = Set.copyOf(disabledPrograms); diagnostics = List.copyOf(diagnostics); }
        public static Summary defaults() { return new Summary(0, null, Set.of(), List.of()); }
    }

    private record Definition(String name, String defaultValue, boolean bool, String constType) {}
    private record Edit(int line, Definition definition) {}
    private record Source(String[] lines, List<Edit> edits) {}
    private record Profile(Map<String, String> values, Set<String> disabled) {}

    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Set<String> ambiguous = new LinkedHashSet<>();
    private final Map<String, String> values;
    private final PackProperties properties;
    private final Summary summary;

    /** Iris stores both folder and ZIP settings as shaderpacks/&lt;exact filename&gt;.txt, Java Properties encoding. */
    public static Request readRequest(Path pack) throws IOException {
        Path settings = pack.resolveSibling(pack.getFileName() + ".txt");
        Map<String, String> overrides = new LinkedHashMap<>();
        if (Files.exists(settings, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS)) throw new PackScanException("non-regular option file: " + settings.getFileName());
            byte[] bytes;
            try (var input = Files.newInputStream(settings, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(65537);
            }
            if (bytes.length > 65536) throw new PackScanException("option file exceeds 64 KiB: " + settings.getFileName());
            Properties saved = new Properties();
            try { saved.load(new ByteArrayInputStream(bytes)); }
            catch (IllegalArgumentException e) { throw new PackScanException("malformed option file: " + e.getMessage()); }
            saved.stringPropertyNames().stream().sorted().forEach(key -> overrides.put(key, saved.getProperty(key)));
        }
        String profile = System.getProperty(PROFILE_PROPERTY);
        return new Request(overrides, profile == null || profile.isBlank() ? null : profile.trim());
    }

    public static IrisPackOptions resolve(PackSource pack, PackReport report, Request request)
        throws IOException, SpirvCompiler.SpirvCompileException {
        return new IrisPackOptions(pack, report, request);
    }

    private IrisPackOptions(PackSource pack, PackReport report, Request request)
        throws IOException, SpirvCompiler.SpirvCompileException {
        if (request.profile() != null && request.profile().length() > 128) throw new PackScanException("shader profile name exceeds 128 characters");
        Set<String> references = new LinkedHashSet<>();
        long chars = 0;
        // Discover before expanding conditions: enabling an option can introduce a previously inactive include.
        // Scope is GLSL files plus their literal include closure, with conservative cross-file ambiguity detection.
        Set<String> available = new LinkedHashSet<>(pack.entries());
        Set<String> queued = new LinkedHashSet<>();
        for (String entry : available)
            if (entry.startsWith(report.shadersRoot()) && entry.matches(".*\\.(?:vsh|fsh|glsl|inc)$")) queued.add(entry);
        List<String> pending = new ArrayList<>(queued);
        for (int index = 0; index < pending.size(); index++) {
            String entry = pending.get(index);
            String text = new String(pack.read(entry), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
            chars += text.length();
            if (chars > IrisSourcePreprocessor.MAX_SOURCE_CHARS) throw new PackScanException("option discovery source budget exceeded");
            String[] raw = text.split("\n", -1);
            String masked = withoutBlockComments(text);
            String[] visible = masked.split("\n", -1);
            List<Edit> edits = new ArrayList<>();
            for (int line = 0; line < visible.length; line++) {
                var reference = REFERENCE.matcher(visible[line]);
                if (reference.matches()) references.add(reference.group(1));
                Definition definition = parse(visible[line]);
                if (definition != null) edits.add(new Edit(line, definition));
            }
            sources.put(entry, new Source(raw, List.copyOf(edits)));
            var includes = INCLUDE.matcher(masked);
            while (includes.find()) {
                String target;
                try { target = PackPaths.resolveWithin(report.shadersRoot(), entry, includes.group(1)); }
                catch (PackScanException ignored) { continue; } // The native callback rejects it if the branch becomes live.
                if (available.contains(target) && queued.add(target)) pending.add(target);
            }
        }
        for (Source source : sources.values()) for (Edit edit : source.edits()) {
            Definition definition = edit.definition();
            if (definition.bool() && definition.constType() == null && !references.contains(definition.name())) continue;
            Definition previous = definitions.putIfAbsent(definition.name(), definition);
            if (previous != null && !previous.equals(definition)) ambiguous.add(definition.name());
            if (definitions.size() > MAX_OPTIONS) throw new PackScanException("too many shader options");
        }
        ambiguous.forEach(definitions::remove);
        List<String> diagnostics = new ArrayList<>();
        Map<String, String> saved = checked(request.overrides(), diagnostics, false);
        Map<String, String> defaults = new LinkedHashMap<>();
        definitions.forEach((name, definition) -> defaults.put(name, definition.defaultValue()));
        Map<String, String> effective = new LinkedHashMap<>(defaults);
        effective.putAll(saved);
        String path = report.shadersRoot() + "shaders.properties";
        String rawProperties = pack.entries().contains(path) ? new String(pack.read(path), StandardCharsets.UTF_8) : "";
        Map<String, Profile> profiles = Map.of();
        PackProperties active = PackProperties.empty();
        boolean stable = false;
        for (int iteration = 0; iteration < 8; iteration++) {
            active = IrisPropertiesPreprocessor.read(rawProperties, macros(effective));
            profiles = profiles(active);
            Map<String, String> next = new LinkedHashMap<>(defaults);
            if (request.profile() != null) {
                Profile selected = profiles.get(request.profile());
                if (selected == null) throw new PackScanException("unknown/inactive shader profile: " + request.profile());
                next.putAll(selected.values());
            }
            next.putAll(saved); // Persisted explicit choices win over the optional session profile baseline.
            if (next.equals(effective)) { stable = true; break; }
            effective = next;
        }
        if (!stable) throw new PackScanException("conditional profile/options did not stabilize after 8 evaluations");
        String matched = null;
        Set<String> disabled = new LinkedHashSet<>();
        List<Map.Entry<String, Profile>> ordered = new ArrayList<>(profiles.entrySet());
        ordered.sort(Comparator.<Map.Entry<String, Profile>>comparingInt(e -> e.getValue().values().size()).reversed());
        for (var entry : ordered) {
            if (effective.entrySet().containsAll(entry.getValue().values().entrySet())) {
                matched = entry.getKey(); disabled.addAll(entry.getValue().disabled()); break;
            }
        }
        Map<String, String> conditions = new LinkedHashMap<>();
        for (String key : active.keys()) {
            if (key.startsWith("program.") && key.endsWith(".enabled")) {
                String program = key.substring(8, key.length() - 8);
                if (!PROGRAM.matcher(program).matches()) throw new PackScanException("unsupported enabled program: " + program);
                conditions.put(program, last(active, key));
            }
        }
        IrisPropertiesPreprocessor.conditions(conditions, macros(effective)).forEach((program, enabled) -> { if (!enabled) disabled.add(program); });
        int changed = 0;
        for (String name : effective.keySet()) if (!effective.get(name).equals(defaults.get(name))) changed++;
        values = Collections.unmodifiableMap(new LinkedHashMap<>(effective));
        properties = active;
        summary = new Summary(changed, matched, disabled, diagnostics);
    }

    public PackProperties properties() { return properties; }
    public Summary summary() { return summary; }

    public String readSource(String entry) throws IOException {
        Source source = sources.get(entry);
        if (source == null) throw new PackScanException("include is not a GLSL source in this pack: " + entry);
        String[] lines = source.lines().clone();
        for (Edit edit : source.edits()) {
            Definition definition = edit.definition();
            if (!values.containsKey(definition.name())) continue;
            String value = values.get(definition.name());
            if (value.equals(definition.defaultValue())) continue;
            lines[edit.line()] = definition.constType() != null
                ? "const " + definition.constType() + " " + definition.name() + " = " + value + ";"
                : definition.bool() ? (value.equals("true") ? "#define " : "// #define ") + definition.name()
                : "#define " + definition.name() + " " + value;
        }
        return String.join("\n", lines);
    }

    private Map<String, String> checked(Map<String, String> proposed, List<String> diagnostics, boolean strict) throws PackScanException {
        if (proposed.size() > MAX_OPTIONS) throw new PackScanException("too many option overrides");
        Map<String, String> valid = new LinkedHashMap<>();
        for (var entry : proposed.entrySet()) {
            Definition definition = definitions.get(entry.getKey());
            if (ambiguous.contains(entry.getKey())) throw new PackScanException("ambiguous shader option: " + entry.getKey());
            if (definition == null) {
                if (strict) throw new PackScanException("profile references unknown option: " + entry.getKey());
                if (diagnostics.size() < 32) diagnostics.add("ignored unknown saved option " + entry.getKey());
                continue;
            }
            String value = entry.getValue();
            if (definition.bool() && !value.equals("true") && !value.equals("false")) {
                if (strict) throw new PackScanException("invalid boolean profile value: " + entry.getKey());
                if (diagnostics.size() < 32) diagnostics.add("invalid boolean option " + entry.getKey() + "; using source default");
                value = definition.defaultValue();
            }
            if (value.length() > 128 || !value.matches(TOKEN)) throw new PackScanException("unsupported option value for " + entry.getKey());
            valid.put(entry.getKey(), value);
        }
        return valid;
    }

    private Map<String, Profile> profiles(PackProperties properties) throws PackScanException {
        Map<String, String> tree = new LinkedHashMap<>();
        for (String key : properties.keys()) if (key.startsWith("profile.")) tree.put(key.substring(8), last(properties, key));
        if (tree.size() > MAX_PROFILES) throw new PackScanException("too many shader profiles");
        Map<String, Profile> resolved = new LinkedHashMap<>();
        int[] work = {0};
        for (String name : tree.keySet()) profile(name, tree, resolved, new LinkedHashSet<>(), work);
        // DFS insertion order must not change the tie-break between equally specific profiles.
        Map<String, Profile> ordered = new LinkedHashMap<>();
        tree.keySet().forEach(name -> ordered.put(name, resolved.get(name)));
        return ordered;
    }

    private Profile profile(String name, Map<String, String> tree, Map<String, Profile> cache, Set<String> stack, int[] work)
        throws PackScanException {
        if (cache.containsKey(name)) return cache.get(name);
        if (!tree.containsKey(name)) throw new PackScanException("unknown inherited profile: " + name);
        if (stack.size() >= 32 || !stack.add(name)) throw new PackScanException("cyclic/deep profile inheritance: " + stack + " -> " + name);
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> disabled = new LinkedHashSet<>();
        for (String token : tree.get(name).split("\\s+")) {
            if (++work[0] > MAX_PROFILE_TOKENS) throw new PackScanException("profile token budget exceeded");
            if (token.isEmpty()) continue;
            if (token.startsWith("profile.")) {
                Profile parent = profile(token.substring(8), tree, cache, stack, work);
                options.putAll(parent.values()); disabled.addAll(parent.disabled());
            } else if (token.startsWith("!program.")) {
                String program = token.substring(9);
                if (!PROGRAM.matcher(program).matches()) throw new PackScanException("unsupported profile program: " + program);
                disabled.add(program);
            } else {
                int split = token.indexOf('=');
                if (split < 0) split = token.indexOf(':');
                if (split >= 0) options.put(token.substring(0, split), token.substring(split + 1));
                else {
                    String option = token.startsWith("!") ? token.substring(1) : token;
                    Definition definition = definitions.get(option);
                    if (definition == null || !definition.bool()) throw new PackScanException("profile switch is not a boolean option: " + option);
                    options.put(option, token.startsWith("!") ? "false" : "true");
                }
            }
        }
        stack.remove(name);
        Profile result = new Profile(checked(options, new ArrayList<>(), true), Set.copyOf(disabled));
        cache.put(name, result);
        return result;
    }

    private Map<String, String> macros(Map<String, String> options) {
        Map<String, String> macros = new LinkedHashMap<>(ENVIRONMENT);
        macros.put("true", "1"); macros.put("false", "0");
        options.forEach((name, value) -> {
            if (definitions.get(name).bool()) { if (value.equals("true")) macros.put(name, "1"); }
            else macros.put(name, value);
        });
        return macros;
    }

    private static String last(PackProperties properties, String key) { return properties.all(key).getLast(); }

    private static Definition parse(String line) {
        var constant = CONSTANT.matcher(line);
        if (constant.matches() && CONST_NAMES.contains(constant.group(2))) {
            boolean bool = constant.group(1).equals("bool");
            String value = constant.group(3);
            if (bool && !value.equals("true") && !value.equals("false")) return null;
            if (bool || constant.group(4) != null && VALUES.matcher(constant.group(4)).find())
                return new Definition(constant.group(2), value, bool, constant.group(1));
        }
        var define = DEFINE.matcher(line);
        if (!define.matches()) return null;
        String name = define.group(2);
        if (name.startsWith("GL_") || name.startsWith("MC_") || name.startsWith("IRIS_") || name.startsWith("BEDRAY_")
            || name.startsWith("__") || name.equals("true") || name.equals("false")) return null;
        String tail = define.group(3) == null ? "" : define.group(3).strip();
        if (tail.isEmpty() || tail.startsWith("//")) return new Definition(name, define.group(1) == null ? "true" : "false", true, null);
        if (define.group(1) != null) return null;
        int comment = tail.indexOf("//");
        if (comment < 0 || !VALUES.matcher(tail.substring(comment + 2)).find()) return null;
        String value = tail.substring(0, comment).strip();
        return value.matches(TOKEN) ? new Definition(name, value, false, null) : null;
    }

    private static Set<String> constNames() {
        Set<String> names = new LinkedHashSet<>(List.of("shadowMapResolution", "shadowDistance", "voxelDistance",
            "shadowDistanceRenderMul", "entityShadowDistanceMul", "shadowIntervalSize", "generateShadowMipmap",
            "generateShadowColorMipmap", "shadowHardwareFiltering", "shadowtex0Mipmap", "shadowtexMipmap", "shadowtex1Mipmap",
            "shadowtex0Nearest", "shadowtexNearest", "shadow0MinMagNearest", "shadowtex1Nearest", "shadow1MinMagNearest",
            "wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife", "centerDepthHalflife", "sunPathRotation",
            "ambientOcclusionLevel", "superSamplingLevel", "noiseTextureResolution"));
        for (int i = 0; i < 8; i++) {
            for (String prefix : List.of("shadowcolor", "shadowColor"))
                for (String suffix : List.of("Mipmap", "Nearest", "MinMagNearest")) names.add(prefix + i + suffix);
            names.add("shadowHardwareFiltering" + i);
        }
        return Set.copyOf(names);
    }

    /** Preserve line numbers and // option annotations, while hiding anything inside block comments. */
    private static String withoutBlockComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean block = false, line = false, quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i), next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (c == '\n') { line = false; out.append(c); continue; }
            if (block) {
                out.append(' ');
                if (c == '*' && next == '/') { out.append(' '); i++; block = false; }
            } else if (!line && !quoted && c == '/' && next == '*') { block = true; out.append("  "); i++; }
            else {
                out.append(c);
                if (!quoted && c == '/' && next == '/') line = true;
                if (!line && c == '"') quoted = !quoted;
                if (quoted && c == '\\' && next != 0) { out.append(next); i++; }
            }
        }
        return out.toString();
    }
}
