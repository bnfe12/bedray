package io.github.andrexarlay.vulkancompat.rt.slang;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * No shader is compiled or kept that nothing runs. On 2026-11-01 five passes (atrous, rc_probe_trace, ssr,
 * temporal_accumulate, the non-live velocity_reproject) were compiled into every jar without a host that registered
 * them, and 21 more modules (about 3,500 lines) were imported by nothing but those passes or by nothing at all. They are
 * deleted; this test keeps the next one from accumulating unseen.
 */
class SlangDeadCodeTest {
    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");
    private static final Path TEST_SHADERS = Path.of("src/test/resources/shaders/slang");
    private static final Path TEST_SOURCES = Path.of("src/test/java");
    private static final List<Path> HOSTS = List.of(Path.of("src/main/java"),
        Path.of("../native/bedray_core/src"), Path.of("../native/bedray_core/include"));
    private static final Pattern IMPORT =
        Pattern.compile("^\\s*(?:__exported\\s+)?import\\s+(?:\"([^\"]+)\"|([\\w.]+))\\s*;", Pattern.MULTILINE);

    @Test
    void everyCompiledPassHasAHost() throws IOException {
        String hosts = concat(HOSTS, ".java", ".cpp", ".h", ".hpp");
        List<String> orphans = new ArrayList<>();
        for (String pass : modules().keySet()) {
            if (!pass.startsWith("passes/")) continue;
            String name = pass.substring("passes/".length(), pass.length() - ".slang".length()).replace('/', '.');
            String base = name.substring(name.lastIndexOf('.') + 1);
            boolean schedulingVariant = base.equals("raygen") || base.equals("ser");
            String transport = schedulingVariant ? name.substring(0, name.lastIndexOf('.')) : name;
            boolean selectedVariant = schedulingVariant && hosts.contains("SlangTracePrograms.select(runtime,")
                && hosts.contains("\"" + transport + "\"")
                && SlangTracePrograms.candidates(transport,
                    io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts.CAP_ALL, true, true, true).contains(name);
            if (!hosts.contains("\"" + name + "\"") && !hosts.contains("\"" + base + "\"") && !selectedVariant) orphans.add(pass);
        }
        assertEquals(List.of(), orphans, "passes compiled into the jar that no host registers");
    }

    @Test
    void everyModuleIsReachedByAPassOrATest() throws IOException {
        Map<String, String> modules = modules();
        Deque<String> work = new ArrayDeque<>();
        modules.keySet().stream().filter(m -> m.startsWith("passes/")).forEach(work::add);
        for (String source : texts(TEST_SHADERS, ".slang")) work.addAll(imports(source, modules));
        String tests = concat(List.of(TEST_SOURCES), ".java");
        for (String module : modules.keySet()) if (tests.contains(module)) work.add(module);
        Set<String> reached = new HashSet<>();
        while (!work.isEmpty()) {
            String module = work.pop();
            if (!modules.containsKey(module) || !reached.add(module)) continue;
            work.addAll(imports(modules.get(module), modules));
        }
        List<String> unreached = modules.keySet().stream().filter(m -> !reached.contains(m)).toList();
        assertEquals(List.of(), unreached, "modules no pass imports and no test reads");
    }

    private static List<String> imports(String source, Map<String, String> modules) {
        List<String> out = new ArrayList<>();
        Matcher m = IMPORT.matcher(source);
        while (m.find()) {
            String target = m.group(1) != null ? m.group(1) : m.group(2).replace('.', '/');
            if (!target.endsWith(".slang")) target += ".slang";
            if (modules.containsKey(target)) {
                out.add(target);
                continue;
            }
            String base = target.substring(target.lastIndexOf('/') + 1);
            for (String key : modules.keySet()) if (key.equals(base) || key.endsWith("/" + base)) out.add(key);
        }
        return out;
    }

    private static Map<String, String> modules() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SHADERS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".slang")).toList())
                out.put(SHADERS.relativize(file).toString().replace('\\', '/'), Files.readString(file));
        }
        return out;
    }

    private static List<String> texts(Path root, String suffix) throws IOException {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> files = Files.walk(root)) {
            List<String> out = new ArrayList<>();
            for (Path file : files.filter(f -> f.toString().endsWith(suffix)).toList()) out.add(Files.readString(file));
            return out;
        }
    }

    private static String concat(List<Path> roots, String... suffixes) throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path root : roots) for (String suffix : suffixes) for (String text : texts(root, suffix)) all.append(text);
        return all.toString();
    }
}
