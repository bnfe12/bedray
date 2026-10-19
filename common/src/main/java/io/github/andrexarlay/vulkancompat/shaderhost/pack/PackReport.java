package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Collectors;

/**
 * Capability report of one shaderpack, built by {@link ShaderpackScanner} from its files only (no GL, no Iris classes).
 * Property-derived values are upper bounds when {@code propertiesConditional} is true (see {@link PackProperties}).
 *
 * @param name                  file/folder name in shaderpacks/
 * @param kind                  zip or folder
 * @param sha256                SHA-256 over all entries (sorted path, NUL, 8-byte length, bytes): identical content, identical hash
 * @param entryCount            regular files
 * @param totalBytes            decompressed bytes of all files
 * @param shadersRoot           pack path of the shaders folder ("shaders/" or "x/shaders/")
 * @param dimensions            program folders: "" (root) and dimension folders, each with program name -> stages
 * @param dimensionIds          folder -> dimension ids from dimension.properties (empty when the file is absent)
 * @param requiredFeatures      iris.features.required (all branches)
 * @param optionalFeatures      iris.features.optional (all branches)
 * @param bufferObjects         bufferObject.N indices declared (SSBO)
 * @param customImages          image.NAME declared
 * @param customTextures        customTexture.NAME declared
 * @param rtDispatch            rt.N.dispatch (Vulkanite RT passes): N -> raw value
 * @param rtGroups              raw rt.groups value or ""
 * @param propertiesConditional shaders.properties has preprocessor branches
 * @param glslVersions          "#version" line value -> number of files
 * @param glslExtensions        "#extension" names used anywhere in the sources
 * @param rtModel               how the pack traces rays
 * @param capabilities          what the pack needs from a host
 * @param extraDeviceExtensions Vulkan device extensions needed beyond the shared RT core (RtDeviceRequirements)
 * @param plannedWork           which delivery stage (I2..I5) has to provide what for this pack
 * @param warnings              non-fatal findings (skipped symlinks, unresolvable files)
 */
public record PackReport(String name, PackSource.Kind kind, String sha256, int entryCount, long totalBytes, String shadersRoot,
                         SortedMap<String, SortedMap<String, Set<ProgramName.Stage>>> dimensions,
                         Map<String, List<String>> dimensionIds,
                         Set<String> requiredFeatures, Set<String> optionalFeatures,
                         Set<Integer> bufferObjects, Set<String> customImages, Set<String> customTextures,
                         SortedMap<Integer, String> rtDispatch, String rtGroups, boolean propertiesConditional,
                         SortedMap<String, Integer> glslVersions, Set<String> glslExtensions,
                         RtModel rtModel, Set<Capability> capabilities, Set<String> extraDeviceExtensions,
                         List<String> plannedWork, List<String> warnings) {

    public enum RtModel {
        /** No ray tracing in the sources. */
        NONE,
        /** Only GL_EXT_ray_query (rayQuery*EXT in compute/fragment): maps onto the core's ray-query path. */
        RAY_QUERY,
        /** traceRayEXT / .rgen/.rchit/.rmiss: needs a VK_KHR_ray_tracing_pipeline pipeline + shader binding table. */
        RAY_TRACING_PIPELINE
    }

    public enum Capability {
        GBUFFERS, SHADOW_PASS, SHADOWCOMP, BEGIN_SETUP_PREPARE, DEFERRED, COMPOSITE, FINAL,
        COMPUTE_PROGRAMS, GEOMETRY_SHADER, TESSELLATION, DIMENSION_FOLDERS, CONDITIONAL_PROPERTIES,
        SSBO, CUSTOM_IMAGES, CUSTOM_TEXTURES, DISTANT_HORIZONS_PROGRAMS, COLORWHEEL_PROGRAMS,
        RAY_QUERY, RAY_TRACING_PIPELINE, VULKANITE_RT_PASSES
    }

    /** All program names of all folders. */
    public Set<String> allPrograms() {
        return dimensions.values().stream().flatMap(m -> m.keySet().stream()).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    /** Programs of one family across all folders. */
    public Set<String> programs(ProgramName.Family family) {
        return allPrograms().stream()
            .filter(p -> {
                ProgramName n = firstStageName(p);
                return n != null && n.family() == family;
            })
            .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private ProgramName firstStageName(String program) {
        for (SortedMap<String, Set<ProgramName.Stage>> progs : dimensions.values()) {
            Set<ProgramName.Stage> st = progs.get(program);
            if (st != null && !st.isEmpty()) {
                return ProgramName.classify(program + "." + st.iterator().next().ext);
            }
        }
        return null;
    }

    /** Copy with another display name/kind: same content found under a different name (cache hit by SHA-256). */
    public PackReport withSource(String newName, PackSource.Kind newKind) {
        return new PackReport(newName, newKind, sha256, entryCount, totalBytes, shadersRoot, dimensions, dimensionIds,
            requiredFeatures, optionalFeatures, bufferObjects, customImages, customTextures, rtDispatch, rtGroups,
            propertiesConditional, glslVersions, glslExtensions, rtModel, capabilities, extraDeviceExtensions, plannedWork, warnings);
    }

    /** One-line summary for the log. */
    public String summary() {
        return "pack=\"" + name + "\" kind=" + kind + " sha256=" + sha256.substring(0, 16) + " entries=" + entryCount
            + " folders=" + dimensions.keySet().stream().map(f -> f.isEmpty() ? "<root>" : f).toList()
            + " programs=" + allPrograms().size() + " rt=" + rtModel + " required=" + requiredFeatures
            + " capabilities=" + capabilities;
    }
}
