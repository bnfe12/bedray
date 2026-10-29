package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the crash class behind {@code hs_err_pid26912.log}.
 *
 * <p>A pass that builds an {@code EntityAtlas} but does not bind its buffers leaves the members
 * undefined, and Slang materialises them as {@code OpUndef} pointers. NVIDIA 617.42 faults in
 * {@code vkCreateComputePipelines} on that SPIR-V, so the failure is a driver crash at pipeline
 * creation rather than a validation error: {@code passes/restir_spatial.slang} shipped exactly
 * that shape until it declared bindings 23/24.
 *
 * <p>The source is the only place the mistake is visible - the manifest cannot know that a
 * struct member was never assigned. This test therefore pairs the two: every pass that declares
 * an {@code EntityAtlas} local must both bind the captured atlas at 23/24 and assign every
 * member that {@code entityTexel}/{@code entitySurfacePbr} read.
 */
class EntityAtlasBindingContractTest {
    /** Matches the local declaration, whatever the pass calls it. */
    private static final Pattern DECLARATION = Pattern.compile("\\bEntityAtlas\\s+(\\w+)\\s*;");

    @Test
    void everyPassThatBuildsAnEntityAtlasBindsAndFillsIt() {
        List<String> names = SlangPassLibrary.names();
        Assumptions.assumeFalse(names.isEmpty(), "no Slang pass was compiled into this build");

        List<String> checked = new ArrayList<>();
        for (String name : names) {
            SlangPassManifest manifest = SlangPassLibrary.load(name).orElseThrow().manifest();
            String source = read("src/main/resources/shaders/slang/" + manifest.source());
            List<String> variables = declaredNames(source);
            if (variables.isEmpty()) {
                continue; // the pass never touches entity geometry
            }
            checked.add(name);
            SlangPassManifest.Binding rects = bindingNumber(manifest, 23);
            SlangPassManifest.Binding texels = bindingNumber(manifest, 24);
            assertNotNull(rects, name + " builds an EntityAtlas but declares no binding 23 (entityRects)");
            assertNotNull(texels, name + " builds an EntityAtlas but declares no binding 24 (entityTexels)");
            assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER, rects.descriptorType(),
                name + ": binding 23 must be the rect buffer the host supplies");
            assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER, texels.descriptorType(),
                name + ": binding 24 must be the texel buffer the host supplies");

            // Every member entityTexel/entitySurfacePbr read has to be assigned before use; an
            // unset member is the OpUndef that crashed pipeline creation.
            for (String variable : variables) {
                for (String member : List.of("rects", "texels", "atlasSize")) {
                    assertTrue(source.contains(variable + "." + member + " ="),
                        name + ": '" + variable + "' never assigns ." + member
                            + ", which Slang turns into an OpUndef pointer");
                }
            }
        }
        assertTrue(checked.contains("restir_spatial"),
            "the spatial stage builds an EntityAtlas and must be part of this contract");
        assertTrue(checked.contains("path_trace"), "the trace pass builds an EntityAtlas");
    }

    private static SlangPassManifest.Binding bindingNumber(SlangPassManifest manifest, int number) {
        for (SlangPassManifest.Binding binding : manifest.bindings()) {
            if (binding.binding() == number) {
                return binding;
            }
        }
        return null;
    }

    private static List<String> declaredNames(String source) {
        List<String> names = new ArrayList<>();
        Matcher matcher = DECLARATION.matcher(source);
        while (matcher.find()) {
            String variable = matcher.group(1);
            if (!names.contains(variable)) {
                names.add(variable);
            }
        }
        return names;
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
