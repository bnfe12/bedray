package io.github.andrexarlay.vulkancompat.rt.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player chooses each ReSTIR technique on its own, so every switch has to be real: a key the config file keeps, a
 * getter the renderer actually reads, and a GPU history that is dropped when the choice changes. A toggle wired to
 * nothing is worse than no toggle, which is why every one of them is pinned to the consumer that acts on it.
 */
class RtRestirSettingsTest {

    private static final String SETTINGS = "src/main/java/io/github/andrexarlay/vulkancompat/rtx/config/RtSettings.java";
    private static final String RENDERER = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtFrameRenderer.java";
    private static final String RAYS_PASS = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtRayEffectsPass.java";
    private static final String SLANG_PASS = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtSlangPathPass.java";
    private static final String REFERENCE_BACKEND = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "ReferencePathTraceBackend.java";
    private static final String NATIVE_BACKEND = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "NativePathTraceBackend.java";
    private static final String UI = "src/main/java/io/github/andrexarlay/vulkancompat/sodium/VulkanSodiumConfig.java";
    private static final String CATALOG = "src/main/java/io/github/andrexarlay/vulkancompat/sodium/SettingsCatalog.java";
    private static final String EN = "src/main/resources/assets/bedray/lang/en_us.json";
    private static final String RU = "src/main/resources/assets/bedray/lang/ru_ru.json";

    /** Each technique: the key the file keeps, the field behind it, and the override-aware getter the renderer reads. */
    private static final String[][] TECHNIQUES = {
        {"rays_restir_temporal", "raysRestirTemporal", "restirTemporal"},
        {"rays_restir_spatial", "raysRestirSpatial", "restirSpatial"},
        {"path_restir_di", "pathRestirDi", "pathRestirDi"},
        {"path_restir_spatial", "pathRestirSpatial", "pathRestirSpatial"} };

    /** A key the writer's prefix loops do not reach is a switch that silently resets on every restart. */
    @Test
    void everySwitchIsRegisteredAndPersisted() {
        String settings = read(SETTINGS);
        String writer = block(settings, "public static void write(Writer w)");
        for (String[] technique : TECHNIQUES) {
            String key = technique[0];
            String field = technique[1];
            String getter = technique[2];
            assertTrue(settings.contains("public static boolean " + field + " = true;"),
                key + " needs an on-by-default field: reuse is what the owner has been running");
            assertTrue(settings.contains("bool(\"" + key + "\", () -> " + field + ", v -> " + field + " = v);"),
                key + " must be registered, or the -Dvkrtx.rt." + key + " override and the loader cannot see it");
            assertTrue(settings.contains("public static boolean " + getter + "() { return b(\"" + key + "\"); }"),
                key + " needs the override-aware getter the renderer reads, so a session -D still wins over the file");
            assertTrue(key.startsWith("rays_") || key.startsWith("path_"),
                "the writer only emits " + key + " while it carries one of the two section prefixes");
            assertTrue(writer.contains("startsWith(\"rays_\")") && writer.contains("startsWith(\"path_\")"),
                "and those prefix loops are the persistence rule this test relies on");
        }
    }

    /** The controller selects reuse per frame; each backend owns allocation and history behind that boundary. */
    @Test
    void thePathTracerSplitsTheRingFromTheReuse() {
        String pass = read(SLANG_PASS);
        assertTrue(pass.contains("return RtConfig.SLANG_BLOCK_EMITTERS && RtConfig.SLANG_RESTIR_DI;"),
            "diRequested() stays the static capability, so flipping the switch never frees a ring mid-frame");
        assertTrue(pass.contains("return diRequested() && RtSettings.pathRestirDi();"),
            "diActive() is the per-frame decision the player controls");
        assertTrue(pass.contains("int diFlags = emitterCount > 0 && diActive() ? DI_FLAG_ENABLED"),
            "the controller hands the chosen backend the current per-frame reuse decision");
        assertTrue(pass.contains("String restir = diActive() ? backend.reservoirState() : \"off\";"),
            "the mode log reports the selected backend's actual ring state");
        String reference = read(REFERENCE_BACKEND);
        assertTrue(reference.contains("if ((dispatch.diFlags() & DI_FLAG_ENABLED) != 0 && emitterCount > 0 && reservoirs != null)"),
            "reference dispatch requires the controller's request and a real ring");
        assertTrue(reference.contains("return di.mode(true, reservoirs != null, false);"),
            "the reference backend separates a fresh ring from a reused one");
        String nativeBackend = read(NATIVE_BACKEND);
        assertTrue(nativeBackend.contains("hasNativeReservoirs()")
                && nativeBackend.contains("frame.flags() | BedrayCoreLayouts.PATH_TRACE_FRAME_NATIVE_DI"),
            "the native backend requires native DI and delegates its ownership through the coarse frame flag");
        String prepare = block(reference, "private void prepareReservoirs(");
        int staticBranch = prepare.indexOf("if (!diRequested()) {");
        assertTrue(staticBranch >= 0 && prepare.indexOf("nativeBuffers.retire(reservoirs)", staticBranch) > staticBranch,
            "allocation follows the static switch only: a settings flip must not free the ring inside a recorded frame");
        assertTrue(!prepare.contains("vkDeviceWaitIdle"),
            "and the retired ring waits for the native owner to drain its uses, never for the whole device");
    }

    /** The ray pass reads the same two switches, each at the place where its own work is decided. */
    @Test
    void theRayPassReadsItsTwoStagesWhereTheyHappen() {
        String renderer = read(RENDERER);
        assertTrue(renderer.contains("if (pass.mode() == RtMode.RAY_TRACED && RtSettings.restirTemporal()\n"
                + "            && (params.flags & RtFrameParams.FLAG_EMISSIVE) != 0) {"),
            "the reuse needs the emissive term it would reuse, and it is the switch that decides whether the ring is handed over");
        assertTrue(renderer.contains("long reservoirAddr = 0L;"),
            "off means the shader is handed a zero reservoir address, which is the shader's own off test");
        String rays = read(RAYS_PASS);
        assertTrue(rays.contains("if (restirSpatial != null && RtSettings.restirSpatial() && params.mode == RtConfig.MODE_LIT"),
            "spatial reuse is gated where the two checker phases are dispatched, not where the pipeline is built");
        assertTrue(rays.contains("private static final boolean RESTIR_SPATIAL"),
            "the session switch still decides whether the kernel exists at all");
    }

    /** A reused record is a claim about the past: any technique change invalidates it, so the frame diff has to see them. */
    @Test
    void aReuseChangeDropsEveryHistoryItInvalidates() {
        String renderer = read(RENDERER);
        assertTrue(renderer.contains("int curReusePolicy = (RtSettings.restirTemporal() ? 1 : 0) | (RtSettings.restirSpatial() ? 2 : 0)\n"
                + "            | (RtSettings.pathRestirDi() ? 4 : 0) | (RtSettings.pathRestirSpatial() ? 8 : 0);"),
            "every reuse switch in one packed int, so a change of any of them is one comparison");
        assertTrue(renderer.contains("|| curReusePolicy != lastFrameReusePolicy"), "and that comparison gates the reset");
        assertTrue(renderer.contains("private static int lastFrameReusePolicy = -1;"),
            "-1 so the first frame is never counted as a change");
        assertTrue(lineContaining(renderer, "resetHistory: preset(").contains("restir({}->{})"),
            "the log names the switch that moved, or a reset is untraceable in a player's log file");
    }

    /** A row the player can see, a description, and both locales: otherwise the switch is a key in a file nobody opens. */
    @Test
    void everySwitchHasARowAndANameInBothLocales() {
        String ui = read(UI);
        String catalog = read(CATALOG);
        JsonObject en = readJson(EN);
        JsonObject ru = readJson(RU);
        for (String[] technique : TECHNIQUES) {
            String key = technique[0];
            assertTrue(ui.contains("boolOpt(builder, \"" + key + "\", true, v -> RtSettings."),
                key + " is not on the settings page");
            assertTrue(catalog.contains("\"" + key + "\""), key + " has no catalog entry");
            assertNotNull(en.get("bedray.opt." + key), key + " has no English name");
            assertNotNull(en.get("bedray.opt." + key + ".desc"), key + " has no English description");
            assertNotNull(ru.get("bedray.opt." + key), key + " has no Russian name");
            assertNotNull(ru.get("bedray.opt." + key + ".desc"), key + " has no Russian description");
        }
        // Spatial reuse merges the records the temporal stage writes, so neither page may offer it alone.
        assertRowNeedsParent(ui, "rays_restir_spatial", "rays_restir_temporal");
        assertRowNeedsParent(ui, "path_restir_spatial", "path_restir_di");
    }

    /**
     * A spatial row is only enabled while its temporal row is on, because that is where its records come from. The screen
     * names the parent through an {@code Identifier} variable, so the pin follows the variable to its key: a row wired to
     * the wrong parent, or to none, fails here.
     */
    private static void assertRowNeedsParent(String ui, String spatialKey, String temporalKey) {
        int at = ui.indexOf("boolOpt(builder, \"" + spatialKey + "\"");
        assertTrue(at >= 0, spatialKey + " is not on the settings page");
        String row = ui.substring(at, ui.indexOf("));", at));
        java.util.regex.Matcher declared = java.util.regex.Pattern
            .compile("Identifier (\\w+) = id\\(\"" + temporalKey + "\"\\);").matcher(ui);
        assertTrue(declared.find(), temporalKey + " has no Identifier the screen can depend on");
        String variable = declared.group(1);
        assertTrue(row.contains("s.readBooleanOption(" + variable + ")"),
            spatialKey + " must be enabled by " + temporalKey + ", not offered on its own");
        assertTrue(row.contains("rtDependencies(modeId, " + variable + ")"),
            spatialKey + " must also declare the dependency, or the screen grays it out on a different mode");
    }

    private static String lineContaining(String source, String needle) {
        for (String line : source.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        throw new AssertionError("missing line: " + needle);
    }

    private static String block(String source, String header) {
        int at = source.indexOf(header);
        assertTrue(at >= 0, "missing block: " + header);
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block: " + header);
    }

    private static JsonObject readJson(String relative) {
        try (Reader reader = Files.newBufferedReader(Path.of(relative), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
