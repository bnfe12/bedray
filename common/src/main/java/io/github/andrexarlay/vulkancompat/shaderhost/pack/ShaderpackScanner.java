package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a {@link PackReport} from a {@link PackSource}: finds the shaders folder like Iris does (root {@code shaders/}, else the
 * shallowest {@code .../shaders/}), hashes every entry, reads shaders.properties / dimension.properties without evaluating
 * their preprocessor branches, lists the programs per folder and scans the GLSL text for versions, extensions and ray-tracing
 * use. Read-only; no GL, no Iris classes, no Minecraft classes.
 */
public final class ShaderpackScanner {
    private static final Set<String> SOURCE_EXT = Set.of("vsh", "fsh", "gsh", "tcs", "tes", "csh", "glsl", "inc",
        "rgen", "rchit", "rahit", "rmiss", "rint");
    private static final Pattern WORLD_FOLDER = Pattern.compile("world-?\\d+");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#\\s*version\\s+(\\d+)(?:[ \\t]+(\\w+))?");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^\\s*#\\s*extension\\s+(GL_\\w+)");
    private static final Pattern TRACE_RAY = Pattern.compile("\\btraceRayEXT\\s*\\(");
    private static final Pattern RAY_QUERY = Pattern.compile("\\brayQueryInitializeEXT\\s*\\(");
    private static final Pattern PBR_SAMPLER = Pattern.compile("\\buniform\\s+sampler2D\\s+(normals|specular)\\b");
    private static final Pattern RT_DISPATCH = Pattern.compile("rt\\.(\\d+)\\.dispatch");
    private static final Pattern BUFFER_OBJECT = Pattern.compile("bufferObject\\.(\\d+)");

    /** Extension needed by packs that use traceRayEXT; the shared RT core only registers ray query (RtDeviceRequirements). */
    public static final String RT_PIPELINE_EXTENSION = "VK_KHR_ray_tracing_pipeline";

    private ShaderpackScanner() {}

    public static PackReport scan(PackSource src) throws IOException {
        List<String> entries = src.entries();
        String root = findShadersRoot(entries);
        if (root == null) {
            throw new PackScanException(src.name() + ": no shaders/ folder (not a shaderpack)");
        }
        List<String> warnings = new ArrayList<>();
        if (src instanceof DirectoryPackSource dir) {
            for (String s : dir.skipped()) {
                warnings.add("skipped non-regular file or link: " + s);
            }
        }

        MessageDigest sha = sha256();
        long total = 0;
        Map<String, String> texts = new HashMap<>();
        for (String e : entries) {
            byte[] data = src.read(e);
            total += data.length;
            sha.update(e.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(longBytes(data.length));
            sha.update(data);
            if (e.startsWith(root) && (SOURCE_EXT.contains(ext(e)) || e.endsWith(".properties"))) {
                texts.put(e, new String(data, StandardCharsets.UTF_8));
            }
        }
        String hash = HexFormat.of().formatHex(sha.digest());

        PackProperties props = texts.containsKey(root + "shaders.properties")
            ? PackProperties.parse(texts.get(root + "shaders.properties")) : PackProperties.empty();
        if (!texts.containsKey(root + "shaders.properties")) {
            warnings.add("no shaders.properties");
        }
        Map<String, List<String>> dimensionIds = new LinkedHashMap<>();
        if (texts.containsKey(root + "dimension.properties")) {
            PackProperties dims = PackProperties.parse(texts.get(root + "dimension.properties"));
            for (String k : dims.keys()) {
                if (k.startsWith("dimension.")) {
                    List<String> ids = new ArrayList<>();
                    for (String v : dims.all(k)) {
                        for (String id : v.trim().split("\\s+")) {
                            if (!id.isEmpty()) {
                                ids.add(id);
                            }
                        }
                    }
                    dimensionIds.put(k.substring("dimension.".length()), List.copyOf(ids));
                }
            }
        }

        // programs per folder
        SortedMap<String, SortedMap<String, Set<ProgramName.Stage>>> folders = new TreeMap<>();
        EnumSet<ProgramName.Family> families = EnumSet.noneOf(ProgramName.Family.class);
        EnumSet<ProgramName.Stage> stages = EnumSet.noneOf(ProgramName.Stage.class);
        for (String e : entries) {
            if (!e.startsWith(root)) {
                continue;
            }
            String rel = e.substring(root.length());
            String folder;
            String file;
            int slash = rel.indexOf('/');
            if (slash < 0) {
                folder = "";
                file = rel;
            } else if (rel.indexOf('/', slash + 1) < 0) {
                folder = rel.substring(0, slash);
                file = rel.substring(slash + 1);
                if (!WORLD_FOLDER.matcher(folder).matches() && !dimensionIds.containsKey(folder)) {
                    continue; // an include folder (lib/, program/, gbuffers/...)
                }
            } else {
                continue;
            }
            ProgramName pn = ProgramName.classify(file);
            if (pn == null) {
                continue;
            }
            folders.computeIfAbsent(folder, f -> new TreeMap<>())
                .computeIfAbsent(pn.program(), p -> EnumSet.noneOf(ProgramName.Stage.class)).add(pn.stage());
            families.add(pn.family());
            stages.add(pn.stage());
        }
        SortedMap<String, SortedMap<String, Set<ProgramName.Stage>>> frozenFolders = new TreeMap<>();
        folders.forEach((f, m) -> {
            SortedMap<String, Set<ProgramName.Stage>> fm = new TreeMap<>();
            m.forEach((p, s) -> fm.put(p, Collections.unmodifiableSet(EnumSet.copyOf(s))));
            frozenFolders.put(f, Collections.unmodifiableSortedMap(fm));
        });

        // properties
        Set<String> required = features(props, "iris.features.required");
        Set<String> optional = features(props, "iris.features.optional");
        Set<Integer> bufferObjects = new TreeSet<>();
        Set<String> images = new TreeSet<>();
        Set<String> customTextures = new TreeSet<>();
        SortedMap<Integer, String> rtDispatch = new TreeMap<>();
        for (String k : props.keys()) {
            Matcher bo = BUFFER_OBJECT.matcher(k);
            Matcher rt = RT_DISPATCH.matcher(k);
            if (bo.matches()) {
                bufferObjects.add(Integer.parseInt(bo.group(1)));
            } else if (rt.matches()) {
                rtDispatch.put(Integer.parseInt(rt.group(1)), props.all(k).get(0));
            } else if (k.startsWith("image.")) {
                images.add(k.substring("image.".length()));
            } else if (k.startsWith("customTexture.")) {
                customTextures.add(k.substring("customTexture.".length()));
            }
        }
        String rtGroups = props.all("rt.groups").isEmpty() ? "" : props.all("rt.groups").get(0);

        // sources
        SortedMap<String, Integer> versions = new TreeMap<>();
        Set<String> extensions = new TreeSet<>();
        boolean traceRay = false;
        boolean rayQuery = false;
        boolean pbrSamplers = false;
        for (Map.Entry<String, String> t : texts.entrySet()) {
            if (t.getKey().endsWith(".properties")) {
                continue;
            }
            String code = stripComments(t.getValue());
            Matcher v = VERSION.matcher(code);
            if (v.find()) {
                String key = v.group(1) + (v.group(2) == null ? "" : " " + v.group(2));
                versions.merge(key, 1, Integer::sum);
            }
            Matcher x = EXTENSION.matcher(code);
            while (x.find()) {
                extensions.add(x.group(1));
            }
            traceRay |= TRACE_RAY.matcher(code).find();
            rayQuery |= RAY_QUERY.matcher(code).find();
            pbrSamplers |= PBR_SAMPLER.matcher(code).find();
        }
        boolean rtStageFiles = stages.stream().anyMatch(ProgramName.Stage::isRayTracing);
        PackReport.RtModel rtModel = traceRay || rtStageFiles ? PackReport.RtModel.RAY_TRACING_PIPELINE
            : rayQuery ? PackReport.RtModel.RAY_QUERY : PackReport.RtModel.NONE;

        EnumSet<PackReport.Capability> caps = EnumSet.noneOf(PackReport.Capability.class);
        if (families.contains(ProgramName.Family.GBUFFERS)) caps.add(PackReport.Capability.GBUFFERS);
        if (families.contains(ProgramName.Family.SHADOW)) caps.add(PackReport.Capability.SHADOW_PASS);
        if (families.contains(ProgramName.Family.SHADOWCOMP)) caps.add(PackReport.Capability.SHADOWCOMP);
        if (families.contains(ProgramName.Family.BEGIN) || families.contains(ProgramName.Family.SETUP)
            || families.contains(ProgramName.Family.PREPARE)) caps.add(PackReport.Capability.BEGIN_SETUP_PREPARE);
        if (families.contains(ProgramName.Family.DEFERRED)) caps.add(PackReport.Capability.DEFERRED);
        if (families.contains(ProgramName.Family.COMPOSITE)) caps.add(PackReport.Capability.COMPOSITE);
        if (families.contains(ProgramName.Family.FINAL)) caps.add(PackReport.Capability.FINAL);
        if (families.contains(ProgramName.Family.DISTANT_HORIZONS)) caps.add(PackReport.Capability.DISTANT_HORIZONS_PROGRAMS);
        if (families.contains(ProgramName.Family.COLORWHEEL)) caps.add(PackReport.Capability.COLORWHEEL_PROGRAMS);
        if (stages.contains(ProgramName.Stage.COMPUTE)) caps.add(PackReport.Capability.COMPUTE_PROGRAMS);
        if (stages.contains(ProgramName.Stage.GEOMETRY)) caps.add(PackReport.Capability.GEOMETRY_SHADER);
        if (stages.contains(ProgramName.Stage.TESS_CONTROL) || stages.contains(ProgramName.Stage.TESS_EVAL)) {
            caps.add(PackReport.Capability.TESSELLATION);
        }
        if (frozenFolders.keySet().stream().anyMatch(f -> !f.isEmpty())) caps.add(PackReport.Capability.DIMENSION_FOLDERS);
        if (props.conditional()) caps.add(PackReport.Capability.CONDITIONAL_PROPERTIES);
        if (!bufferObjects.isEmpty() || required.contains("SSBO")) caps.add(PackReport.Capability.SSBO);
        if (!images.isEmpty() || required.contains("CUSTOM_IMAGES")) caps.add(PackReport.Capability.CUSTOM_IMAGES);
        if (!customTextures.isEmpty()) caps.add(PackReport.Capability.CUSTOM_TEXTURES);
        if (rtModel == PackReport.RtModel.RAY_TRACING_PIPELINE) caps.add(PackReport.Capability.RAY_TRACING_PIPELINE);
        if (rayQuery) caps.add(PackReport.Capability.RAY_QUERY);
        if (!rtDispatch.isEmpty() || families.contains(ProgramName.Family.RAY)) caps.add(PackReport.Capability.VULKANITE_RT_PASSES);

        Set<String> extraExt = rtModel == PackReport.RtModel.RAY_TRACING_PIPELINE ? Set.of(RT_PIPELINE_EXTENSION) : Set.of();

        int programSources = 0;
        for (SortedMap<String, Set<ProgramName.Stage>> m : frozenFolders.values()) {
            for (Set<ProgramName.Stage> s : m.values()) {
                programSources += s.size();
            }
        }
        if (programSources == 0) {
            warnings.add("no program sources in " + root + " (incomplete or unpacked-patch folder?)");
        }
        List<String> planned = plannedWork(frozenFolders, versions, programSources, caps, bufferObjects, images, customTextures,
            pbrSamplers, rtModel, rtDispatch, required);

        return new PackReport(src.name(), src.kind(), hash, entries.size(), total, root,
            Collections.unmodifiableSortedMap(frozenFolders), Collections.unmodifiableMap(dimensionIds),
            Collections.unmodifiableSet(required), Collections.unmodifiableSet(optional),
            Collections.unmodifiableSet(bufferObjects), Collections.unmodifiableSet(images), Collections.unmodifiableSet(customTextures),
            Collections.unmodifiableSortedMap(rtDispatch), rtGroups, props.conditional(),
            Collections.unmodifiableSortedMap(versions), Collections.unmodifiableSet(extensions),
            rtModel, Collections.unmodifiableSet(caps), extraExt, List.copyOf(planned), List.copyOf(warnings));
    }

    private static List<String> plannedWork(SortedMap<String, SortedMap<String, Set<ProgramName.Stage>>> folders,
                                            SortedMap<String, Integer> versions, int programSources,
                                            Set<PackReport.Capability> caps, Set<Integer> bufferObjects, Set<String> images,
                                            Set<String> customTextures, boolean pbrSamplers, PackReport.RtModel rtModel,
                                            SortedMap<Integer, String> rtDispatch, Set<String> required) {
        List<String> out = new ArrayList<>();
        boolean legacy = versions.keySet().stream().anyMatch(v -> v.contains("compatibility") || versionNumber(v) < 150);
        out.add("I2: preprocess + compile " + programSources + " program sources to SPIR-V; GLSL versions " + versions
            + (legacy ? "; legacy/compatibility-profile GLSL (gl_Vertex, gl_FragData, ftransform...) must be rewritten" : "")
            + (caps.contains(PackReport.Capability.CONDITIONAL_PROPERTIES) ? "; shaders.properties needs option-aware evaluation" : ""));
        StringBuilder i3 = new StringBuilder("I3: pass graph for folders ").append(folders.keySet().stream()
            .map(f -> f.isEmpty() ? "<root>" : f).toList()).append(" (");
        List<String> parts = new ArrayList<>();
        for (PackReport.Capability c : List.of(PackReport.Capability.GBUFFERS, PackReport.Capability.SHADOW_PASS,
            PackReport.Capability.SHADOWCOMP, PackReport.Capability.BEGIN_SETUP_PREPARE, PackReport.Capability.DEFERRED,
            PackReport.Capability.COMPOSITE, PackReport.Capability.FINAL, PackReport.Capability.COMPUTE_PROGRAMS,
            PackReport.Capability.GEOMETRY_SHADER, PackReport.Capability.TESSELLATION)) {
            if (caps.contains(c)) {
                parts.add(c.name().toLowerCase(Locale.ROOT));
            }
        }
        i3.append(String.join(", ", parts)).append(')');
        if (!bufferObjects.isEmpty()) i3.append("; SSBO bufferObject ").append(bufferObjects);
        if (!images.isEmpty()) i3.append("; custom images ").append(images.size());
        if (!customTextures.isEmpty()) i3.append("; custom textures ").append(customTextures.size());
        if (!required.isEmpty()) i3.append("; required features ").append(required);
        out.add(i3.toString());
        out.add(pbrSamplers ? "I4: normals/specular samplers used: map LabPBR/MER to the RT material table"
            : "I4: no normals/specular samplers found: albedo-only material mapping");
        switch (rtModel) {
            case NONE -> out.add("I5: pack does no ray tracing; RT lighting only if the host injects it (not a pack feature)");
            case RAY_QUERY -> out.add("I5: bind the core TLAS to the pack's ray queries (core already has VK_KHR_ray_query)");
            case RAY_TRACING_PIPELINE -> out.add("I5: " + (rtDispatch.isEmpty() ? "" : rtDispatch.size() + " Vulkanite rt.N passes, ")
                + "traceRayEXT needs " + RT_PIPELINE_EXTENSION + " (not registered by the core: ray query only), shader binding table,"
                + " and the pack's own descriptor ABI (TLAS/camera/geometry buffers as the pack declares them)");
        }
        return out;
    }

    /** Root "shaders/" wins; otherwise the shallowest ".../shaders/" (ties: lexicographically first), as Iris walks the zip. */
    static String findShadersRoot(List<String> entries) {
        String best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (String e : entries) {
            String[] segs = e.split("/");
            for (int i = 0; i < segs.length - 1 && i <= bestDepth; i++) {
                if (segs[i].equals("shaders")) {
                    String cand = String.join("/", java.util.Arrays.copyOf(segs, i + 1)) + "/";
                    if (i < bestDepth || cand.compareTo(best) < 0) {
                        best = cand;
                        bestDepth = i;
                    }
                    break;
                }
            }
        }
        return best;
    }

    private static Set<String> features(PackProperties props, String key) {
        Set<String> out = new TreeSet<>();
        for (String v : props.all(key)) {
            for (String f : v.trim().split("\\s+")) {
                if (!f.isEmpty()) {
                    out.add(f.toUpperCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** Removes // and block comments (keeps line breaks so ^-anchored patterns still work). String literals do not occur in GLSL. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) {
                    if (s.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int versionNumber(String v) {
        int sp = v.indexOf(' ');
        try {
            return Integer.parseInt(sp < 0 ? v : v.substring(0, sp));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String ext(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) v;
            v >>>= 8;
        }
        return b;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }
}
