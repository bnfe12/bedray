package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@link NativeBundle.Entry#detail()} is printed to latest.log on each launch and shown in the natives screen, so none of them
 * may carry the real user profile directory.
 */
class NativeBundleTest {
    @Test
    void detailOfAMissingBundledFileNamesTheFolderWithoutTheUserProfile() {
        String home = System.getProperty("user.home");
        Path natives = Path.of(home, "bedray-unit-test-natives");
        NativeCatalog.Slot slot = NativeCatalog.GROUPS.stream()
            .flatMap(g -> g.slots().stream())
            .filter(NativeCatalog.Slot::bundled)
            .findFirst()
            .orElseThrow();

        // Pinned: a gameDir left over from another test would alias the home, and the <home> assertion would then be about that test.
        LogPaths.setGameDir(null);
        NativeBundle.Entry entry = NativeBundle.extractOne(natives, slot);

        // The unit test jar has no /META-INF/natives entry, so this is the branch that quotes the target folder back to the user.
        assertEquals(NativeBundle.Outcome.NOT_EMBEDDED, entry.outcome(), String.valueOf(entry.detail()));
        assertFalse(entry.detail().contains(home), entry.detail());
        assertTrue(entry.detail().contains("<home>"), entry.detail());
    }
}
