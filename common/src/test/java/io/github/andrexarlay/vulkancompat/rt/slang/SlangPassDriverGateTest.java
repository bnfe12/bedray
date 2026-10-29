package io.github.andrexarlay.vulkancompat.rt.slang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The driver gate has to fail closed on the one defect the driver cannot survive: a module that reads a
 * descriptor handle no binding ever assigned. {@code nvoglv64.dll} takes an access violation inside
 * {@code vkCreateComputePipelines} on such a module, which is not a {@code VkResult} and not a Java
 * exception - the JVM dies with the game, with no log line. The rule therefore reads the compiled bytes,
 * so fixing a pass in Slang lifts its own refusal.
 *
 * <p>What this file does not do is trust a name list. The headless replay of 2026-10-29 faulted on
 * {@code path_trace} and {@code restir_spatial}, and the live frame registered both at 20:50:16 in
 * {@code logs/latest.log} of the testbed instance - a replay fault is only a refusal when no live frame has
 * ever registered the module either.
 */
class SlangPassDriverGateTest {

    /** Registered by a live frame on the owner's GPU, so a refusal here is a feature lost. */
    private static final Set<String> LIVE_REGISTERED = Set.of("path_trace", "path_trace_scene", "restir_spatial",
        "path_display", "sodium_dequantize", "particles_composite", "bloom_down", "bloom_up", "bloom_apply",
        "bloom_prefilter_down", "atrous", "composite", "ssr", "temporal_accumulate", "volumetric_apply");

    @Test
    void no_bundled_module_reads_an_unassigned_descriptor_handle() {
        List<String> scanned = new ArrayList<>();
        for (String name : SlangPassLibrary.names()) {
            Assumptions.assumeTrue(SlangPassLibrary.available(name), name + " is not bundled here");
            Optional<SlangPass> pass = SlangPassLibrary.load(name);
            assertTrue(pass.isPresent(), name + " has no loadable artifact");
            int undefs = SlangPassDriverGate.undefinedDescriptorPointers(pass.get().spirv());
            assertEquals(0, undefs, name + " would crash the JVM: " + undefs
                + " OpUndef descriptor pointer(s) - bind the resource or delete the code path");
            scanned.add(name);
        }
        assertTrue(scanned.size() >= 19, "the scan must cover the whole pass set, not an empty classpath: "
            + scanned);
    }

    @Test
    void the_march_module_is_clean_after_losing_its_entity_leg() {
        Assumptions.assumeTrue(SlangPassLibrary.available("volumetric_march"));
        byte[] spirv = SlangPassLibrary.load("volumetric_march").orElseThrow().spirv();
        assertEquals(0, SlangPassDriverGate.undefinedDescriptorPointers(spirv));
        assertNull(SlangPassDriverGate.blocker("volumetric_march", spirv),
            "light shafts were refused only while the module carried an unassigned EntityGeometry");
    }

    /** A fixture, not a real module: the scanner must fire on the exact shape Slang emits. */
    @Test
    void an_undef_pointer_into_a_descriptor_slot_is_counted() {
        assertEquals(1, SlangPassDriverGate.undefinedDescriptorPointers(
            module(op(4, 32), /* pointer id */ 10, /* UniformConstant */ 0, /* pointee */ 8,
                   op(3, 1), /* result type */ 10, /* result id */ 11)));
        assertEquals(2, SlangPassDriverGate.undefinedDescriptorPointers(
            module(op(4, 32), 10, 12, 8,
                   op(3, 1), 10, 11,
                   op(3, 1), 10, 12)));
        assertEquals(0, SlangPassDriverGate.undefinedDescriptorPointers(
            module(op(4, 32), 10, /* Function storage, not a descriptor */ 7, 8,
                   op(3, 1), 10, 11)), "a scratch pointer is not a descriptor read");
        assertEquals(0, SlangPassDriverGate.undefinedDescriptorPointers(
            module(op(3, 20), 1, 2, op(3, 1), 2, 11)), "an undef of a non-pointer type is harmless");
        assertFalse(SlangPassDriverGate.permits("any_pass",
            module(op(4, 32), 10, 2, 8, op(3, 1), 10, 11)));
    }

    @Test
    void a_module_with_the_defect_is_refused_even_when_its_name_is_proven() {
        byte[] broken = module(op(4, 32), 10, 0, 8, op(3, 1), 10, 11);
        assertNull(SlangPassDriverGate.blocker("path_trace", null));
        String reason = SlangPassDriverGate.blocker("path_trace", broken);
        assertNotNull(reason, "the bytes outrank the name: path_trace is proven only as long as it compiles clean");
        assertTrue(reason.contains("OpUndef"), reason);
        assertTrue(reason.contains(SlangPassDriverGate.PASSES_PROPERTY), "it has to say how to override: " + reason);
    }

    @Test
    void a_replay_fault_no_live_frame_ever_overrules_is_refused() {
        for (String name : Set.of("volumetric_march_scene", "rc_probe_trace", "velocity_reproject")) {
            String reason = SlangPassDriverGate.blocker(name);
            assertNotNull(reason, name + " must stay out of the driver until a live frame registers it");
            assertTrue(reason.contains(SlangPassDriverGate.PASSES_PROPERTY), name + " has to say how to override");
        }
        assertEquals(Set.of("volumetric_march_scene", "rc_probe_trace", "velocity_reproject"),
            SlangPassDriverGate.blockedNames());
    }

    @Test
    void the_passes_a_live_frame_registered_are_permitted() {
        for (String name : LIVE_REGISTERED) {
            assertNull(SlangPassDriverGate.blocker(name), name + " runs in the game, so the gate must not stop it");
            assertTrue(SlangPassDriverGate.permits(name, null));
        }
    }

    @Test
    void a_null_or_empty_name_is_refused() {
        assertNotNull(SlangPassDriverGate.blocker(null));
        assertNotNull(SlangPassDriverGate.blocker(""));
    }

    @Test
    void the_property_overrides_for_one_pass_and_for_every_pass() {
        try {
            System.setProperty(SlangPassDriverGate.PASSES_PROPERTY, "volumetric_march_scene,path_trace");
            assertNull(SlangPassDriverGate.blocker("volumetric_march_scene"));
            assertNull(SlangPassDriverGate.blocker("volumetric_march_scene",
                module(op(4, 32), 10, 0, 8, op(3, 1), 10, 11)));

            System.setProperty(SlangPassDriverGate.PASSES_PROPERTY, "all");
            assertNull(SlangPassDriverGate.blocker("rc_probe_trace"));
        } finally {
            System.clearProperty(SlangPassDriverGate.PASSES_PROPERTY);
        }
        assertNotNull(SlangPassDriverGate.blocker("volumetric_march_scene"),
            "clearing the property restores the default");
    }

    /**
     * A gate nobody calls is a comment. The refusal has to happen in {@code program} before the register call,
     * because {@code SlangProgram.register} is the code that enters the driver.
     */
    @Test
    void the_runtime_refuses_before_it_registers() throws IOException {
        String source = read(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangRuntime.java"));
        int refusal = source.indexOf("SlangPassDriverGate.blocker(name, pass.get().spirv())");
        int register = source.indexOf("SlangProgram.register(library, core, pass.get())");
        assertTrue(refusal > 0, "SlangRuntime must screen the module bytes it is about to register");
        assertTrue(register > 0, "the register call this test anchors on was renamed");
        assertTrue(refusal < register, "the gate has to refuse before the runtime reaches the driver");
    }

    @Test
    void blocked_names_are_all_bundled_in_the_jar() {
        Set<String> bundled = Set.copyOf(SlangPassLibrary.names());
        for (String name : SlangPassDriverGate.blockedNames()) {
            assertTrue(bundled.contains(name), name + " is not in the jar, so the gate is guarding nothing");
        }
    }

    /** SPIR-V words after the five-word header, little endian, each instruction already carrying its size. */
    private static byte[] module(int... wordsAfterHeader) {
        ByteBuffer buffer = ByteBuffer.allocate((5 + wordsAfterHeader.length) * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x07230203).putInt(0x00010500).putInt(0).putInt(64).putInt(0);
        for (int word : wordsAfterHeader) {
            buffer.putInt(word);
        }
        return buffer.array();
    }

    /** One SPIR-V instruction word: the word count shares the word with the opcode. */
    private static int op(int wordCount, int opcode) {
        return (wordCount << 16) | opcode;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
