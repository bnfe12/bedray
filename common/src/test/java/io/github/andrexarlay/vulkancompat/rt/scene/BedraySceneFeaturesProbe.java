package io.github.andrexarlay.vulkancompat.rt.scene;

import java.nio.file.Path;

/** Real FFM ABI-v2 compatibility probe; requires no Vulkan device. Arguments are pairs: DLL path, expected features. */
public final class BedraySceneFeaturesProbe {
    public static void main(String[] args) {
        if (args.length == 0 || args.length % 2 != 0) throw new IllegalArgumentException("expected DLL/features pairs");
        for (int i = 0; i < args.length; i += 2) {
            var path = Path.of(args[i]);
            try (var library = BedraySceneNative.load(path)) {
                int features = library.features();
                int expected = Integer.parseInt(args[i + 1]);
                if (features != expected) throw new AssertionError("features=" + features + " expected=" + expected);
                System.out.println(path.getFileName() + " abi=" + library.abiInfo().abiVersion() + " features=" + features + " expected=" + expected);
            }
        }
    }
}
