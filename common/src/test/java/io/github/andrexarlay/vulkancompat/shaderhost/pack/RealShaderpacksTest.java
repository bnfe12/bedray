package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I1 on real shaderpacks, read IN PLACE from the user's launcher instances (pack licenses forbid copying them into the repo).
 * Skipped when no instance folder exists (CI, other machines). Writes the capability table to
 * {@code common/build/reports/shaderhost/real-packs.md}. Roots can be overridden with {@code -Dvkrtx.test.instanceRoots=a;b}.
 */
class RealShaderpacksTest {
    private static final List<Path> DEFAULT_ROOTS = List.<Path>of();

    private static List<Path> shaderpackDirs() throws IOException {
        String prop = System.getProperty("vkrtx.test.instanceRoots");
        List<Path> roots = new ArrayList<>();
        if (prop != null && !prop.isBlank()) {
            for (String s : prop.split(";")) {
                roots.add(Path.of(s.trim()));
            }
        } else {
            roots.addAll(DEFAULT_ROOTS);
        }
        List<Path> dirs = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path inst : ds) {
                    Path sp = inst.resolve("minecraft").resolve("shaderpacks");
                    if (Files.isDirectory(sp)) {
                        dirs.add(sp);
                    }
                }
            }
        }
        return dirs;
    }

    @Test
    void realPacksCapabilityTable() throws IOException {
        List<Path> dirs = shaderpackDirs();
        Assumptions.assumeFalse(dirs.isEmpty(), "no launcher instances on this machine");
        PackReportCache cache = new PackReportCache(ScanLimits.DEFAULT);
        Map<String, PackReport> bySha = new LinkedHashMap<>();
        Map<String, List<String>> where = new LinkedHashMap<>();
        List<String> refused = new ArrayList<>();
        for (Path dir : dirs) {
            for (ShaderpackDiscovery.Candidate c : ShaderpackDiscovery.list(dir)) {
                try {
                    PackReport r = cache.get(c.path());
                    bySha.putIfAbsent(r.sha256(), r);
                    where.computeIfAbsent(r.sha256(), k -> new ArrayList<>()).add(c.name());
                } catch (PackScanException e) {
                    refused.add(c.name() + ": " + e.getMessage());
                }
            }
        }
        Assumptions.assumeFalse(bySha.isEmpty(), "no shaderpacks found");

        StringBuilder md = new StringBuilder();
        md.append("| pack | kind | sha256[0:12] | folders | programs (gbuf/shadow/deferred/composite/final/compute src) | GLSL | features req / opt | SSBO/images | RT | extra device ext |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (PackReport r : bySha.values()) {
            int compute = 0;
            for (var f : r.dimensions().values()) {
                for (var st : f.values()) {
                    if (st.contains(ProgramName.Stage.COMPUTE)) {
                        compute++;
                    }
                }
            }
            md.append("| ").append(r.name()).append(where.get(r.sha256()).size() > 1 ? " (+" + (where.get(r.sha256()).size() - 1) + " copies)" : "")
                .append(" | ").append(r.kind())
                .append(" | ").append(r.sha256(), 0, 12)
                .append(" | ").append(r.dimensions().keySet().stream().map(f -> f.isEmpty() ? "root" : f).toList())
                .append(" | ").append(r.programs(ProgramName.Family.GBUFFERS).size()).append('/')
                .append(r.programs(ProgramName.Family.SHADOW).size()).append('/')
                .append(r.programs(ProgramName.Family.DEFERRED).size()).append('/')
                .append(r.programs(ProgramName.Family.COMPOSITE).size()).append('/')
                .append(r.programs(ProgramName.Family.FINAL).size()).append('/')
                .append(compute)
                .append(" | ").append(r.glslVersions())
                .append(" | ").append(r.requiredFeatures()).append(" / ").append(r.optionalFeatures())
                .append(" | ").append(r.bufferObjects().size()).append('/').append(r.customImages().size())
                .append(" | ").append(r.rtModel()).append(r.rtDispatch().isEmpty() ? "" : " rt.N=" + r.rtDispatch().size())
                .append(r.programs(ProgramName.Family.RAY).isEmpty() ? "" : " ray=" + r.programs(ProgramName.Family.RAY))
                .append(" | ").append(r.extraDeviceExtensions().isEmpty() ? "-" : r.extraDeviceExtensions())
                .append(" |\n");
        }
        md.append("\n### planned work per pack\n");
        for (PackReport r : bySha.values()) {
            md.append("\n**").append(r.name()).append("** caps=").append(r.capabilities()).append('\n');
            for (String w : r.plannedWork()) {
                md.append("- ").append(w).append('\n');
            }
            for (String w : r.warnings()) {
                md.append("- warning: ").append(w).append('\n');
            }
        }
        if (!refused.isEmpty()) {
            md.append("\n### refused\n");
            refused.forEach(s -> md.append("- ").append(s).append('\n'));
        }
        Path out = Path.of("build", "reports", "shaderhost", "real-packs.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
        System.out.println(md);

        PackReport dirt = find(bySha, where, "dirt rt");
        if (dirt != null) {
            assertEquals(PackReport.RtModel.RAY_TRACING_PIPELINE, dirt.rtModel());
            assertEquals(Set.of(0, 1, 2), dirt.rtDispatch().keySet());
            assertEquals(Set.of("ray0", "ray0_0", "ray1", "ray1_0", "ray2", "ray2_0"), dirt.programs(ProgramName.Family.RAY));
            assertEquals(Set.of("CUSTOM_IMAGES", "SSBO"), dirt.requiredFeatures());
            assertTrue(dirt.capabilities().contains(PackReport.Capability.COMPUTE_PROGRAMS));
            assertEquals(Set.of(ShaderpackScanner.RT_PIPELINE_EXTENSION), dirt.extraDeviceExtensions());
        }
        PackReport comp = find(bySha, where, "complementaryreimagined_r5.9.3.zip");
        if (comp != null) {
            assertEquals(PackReport.RtModel.NONE, comp.rtModel());
            assertTrue(comp.dimensions().keySet().containsAll(Set.of("world0", "world-1", "world1")));
            assertTrue(comp.dimensions().get("world0").keySet().containsAll(Set.of("gbuffers_terrain", "deferred1", "composite", "final", "shadow")));
            assertTrue(comp.propertiesConditional());
        }
        Assumptions.assumeTrue(dirt != null || comp != null, "neither Dirt RT nor Complementary r5.9.3 is installed");
    }

    private static PackReport find(Map<String, PackReport> bySha, Map<String, List<String>> where, String nameLower) {
        for (PackReport r : bySha.values()) {
            for (String n : where.get(r.sha256())) {
                if (n.toLowerCase(Locale.ROOT).startsWith(nameLower)) {
                    return r;
                }
            }
        }
        return null;
    }
}
