package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manifest contract check carved out of {@link RtPathTraceSlangPass}: the push-block width, the binding count and
 * descriptor types, and every element stride now live in {@link RtPathManifestValidation}. The pass keeps the binding
 * contract it supplies (the {@code Expected} record, the {@code EXPECTED}/{@code PT_SPATIAL_EXPECTED} tables and the byte
 * constants) and delegates the three checks, so a later edit cannot quietly pull the validation algorithm back into the
 * monolith, nor move the binding tables the API tests read out of the pass.
 */
class RtPathManifestValidationTest {

    private static final Path JAVA = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world");
    private static final Path OWNER = JAVA.resolve("RtPathManifestValidation.java");
    private static final Path PASS = JAVA.resolve("RtPathTraceSlangPass.java");

    @Test
    void theOwnerHoldsTheManifestValidation() throws IOException {
        assertTrue(Files.exists(OWNER), "the manifest validation owner exists");
        String owner = read(OWNER);
        assertTrue(owner.contains("static void requireManifest(SlangProgram program, boolean radianceCascades)"),
            "the trace manifest check moved into the owner");
        assertTrue(owner.contains("int count = EXPECTED.size() + (radianceCascades ? 2 : 0);"),
            "the radiance-cascade binding allowance is the owner's");
        assertTrue(owner.contains("for (Expected want : EXPECTED)"), "the binding walk is the owner's");
        assertTrue(owner.contains("requireElementBytes(manifest, \"reservoirs\", RESERVOIR_BYTES)"),
            "the DI reservoir stride is re-checked here");
        assertTrue(owner.contains("requireElementBytes(manifest, \"outMotionBuffer\", MOTION_ELEMENT_BYTES);"),
            "the NRD motion stride is re-checked here");
        assertTrue(owner.contains("requireElementBytes(manifest, \"outPathStats\", PATH_STATS_WORD_BYTES);"),
            "the path-stats word stride is re-checked here");
        assertTrue(owner.contains("RtRadianceCascadesContract.requireFieldBindings(manifest);"),
            "the cascade field bindings are still required when the flag is set");
        assertTrue(owner.contains("static void requirePtSpatialManifest(SlangPassManifest manifest)"),
            "the spatial stage check moved into the owner");
        assertTrue(owner.contains("for (Expected want : PT_SPATIAL_EXPECTED)"), "the spatial binding walk is the owner's");
        assertTrue(owner.contains("private static void requireElementBytes(SlangPassManifest manifest, String name, int expected)"),
            "the shared stride helper moved with the checks");
    }

    @Test
    void theOwnerReadsThePassContractItValidates() throws IOException {
        String owner = read(OWNER);
        assertTrue(owner.contains("import static io.github.andrexarlay.vulkancompat.rt.world.RtPathTraceSlangPass.*;"),
            "the owner validates against the pass's own tables and byte constants");
        assertTrue(owner.contains("if (manifest.pushConstantBytes() != PUSH_BYTES)"),
            "the push block is checked before any binding is read");
    }

    @Test
    void theFacadeDelegatesTheManifestChecks() throws IOException {
        String pass = read(PASS);
        assertTrue(pass.contains("RtPathManifestValidation.requireManifest(program, radianceCascades);"),
            "the two-arg trace check delegates to the owner");
        assertTrue(pass.contains("RtPathManifestValidation.requirePtSpatialManifest(manifest);"),
            "the spatial check delegates to the owner");
        assertFalse(pass.contains("for (Expected want : EXPECTED)"), "the binding walk moved to the owner");
        assertFalse(pass.contains("for (Expected want : PT_SPATIAL_EXPECTED)"), "the spatial walk moved to the owner");
        assertFalse(pass.contains("private static void requireElementBytes("), "the stride helper moved to the owner");
        assertFalse(pass.contains("requireElementBytes(manifest, \"reservoirs\", RESERVOIR_BYTES)"),
            "the reservoir stride check moved to the owner");
    }

    @Test
    void theBindingContractStaysInThePass() throws IOException {
        String pass = read(PASS);
        assertTrue(pass.contains("record Expected(String name, int descriptorType)"),
            "the expected-binding type is still the pass's public contract");
        assertTrue(pass.contains("static final List<Expected> EXPECTED = List.of("),
            "the trace binding table stays where the API tests read it");
        assertTrue(pass.contains("static final List<Expected> PT_SPATIAL_EXPECTED = List.of("),
            "the spatial binding table stays in the pass");
        assertTrue(pass.contains("new Expected(\"outMotionBuffer\", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER),"),
            "the motion binding declaration stays in the pass table");
        assertTrue(pass.contains("static final int RESERVOIR_BYTES = 64;"), "the byte constants stay in the pass");
        assertTrue(pass.contains("static final int MOTION_ELEMENT_BYTES = 16;"), "the motion stride constant stays in the pass");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}