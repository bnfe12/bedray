package io.github.andrexarlay.vulkancompat.rtx.framegen;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;

import org.junit.jupiter.api.Test;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertNull;

/** L2: optional autotest-only native exports do not become requirements for the Streamline shim. */
public class SlNativeTest {
    @Test
    public void absentFocusHelperIsOptional() {
        SymbolLookup noSymbols = name -> java.util.Optional.empty();
        assertNull(SlNative.bindOptional(Linker.nativeLinker(), noSymbols, "vkb_focus_game_window", FunctionDescriptor.of(JAVA_INT)));
    }
}
