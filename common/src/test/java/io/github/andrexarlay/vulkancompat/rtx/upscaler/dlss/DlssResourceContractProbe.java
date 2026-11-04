package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.rr.DlssRayReconstruction;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.rr.DlssRrParams;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Real host FFM bindings against CI-built actual wrappers. Fault fixtures never call a GPU. */
public final class DlssResourceContractProbe {
    private static void require(boolean condition, Object message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 2) throw new IllegalArgumentException("DLL path and ngx/sl/production");
        Path library = Path.of(args[0]);
        try (DlssNative sr = DlssNative.load(library); Arena control = Arena.ofConfined()) {
            require(sr.supportsSafeResourceRetirement(), "resource contract v1 missing");
            if (args[1].equals("production")) {
                System.out.println(library.getFileName() + " production FFM resource contract=1; no GPU initialized");
                return;
            }
            var lookup = SymbolLookup.libraryLookup(library, control);
            var linker = Linker.nativeLinker();
            MethodHandle reset = linker.downcallHandle(lookup.find("vendor_test_reset").orElseThrow(), FunctionDescriptor.ofVoid());
            MethodHandle fault = linker.downcallHandle(lookup.find("vendor_test_fail").orElseThrow(),
                FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT));
            sr.shutdown(); reset.invokeExact();
            var caps = new DlssNative.Caps[1];
            require(sr.init(1, 2, 3, 0, 0, Path.of("."), Path.of("."), null, null, caps).ok(), "fake init");
            boolean ngx = args[1].equals("ngx");
            int createOperation = ngx ? 2 : 8;
            fault.invokeExact(createOperation, 1, 1);
            long[] partial = new long[1];
            require(!sr.createFeature(42, 64, 48, 128, 96, DlssQuality.QUALITY, 0, partial).ok(), "injected SR create succeeded");
            require(partial[0] != 0, "FFM discarded partial SR ownership");
            require(sr.releaseFeature(partial[0]).ok(), "partial SR cleanup");

            try (var rr = DlssRayReconstruction.load(library)) {
                fault.invokeExact(createOperation, 1, 1);
                partial[0] = 0;
                var info = new DlssRrParams.CreateInfo(64, 48, 128, 96, DlssQuality.QUALITY, 0, false, 1, 1);
                require(!rr.createFeature(42, info, partial).ok(), "injected RR create succeeded");
                require(partial[0] != 0, "FFM discarded partial RR ownership");
                require(rr.releaseFeature(partial[0]).ok(), "partial RR cleanup");
            }
            long[] current = new long[1];
            require(sr.createFeature(42, 64, 48, 128, 96, DlssQuality.QUALITY, 0, current).ok(), "SR create");
            var input = new DlssNative.Image(12, 22, 97, 64, 48, 1, false);
            var output = new DlssNative.Image(11, 21, 97, 128, 96, 1, true);
            var eval = new DlssNative.EvalInfo(true, input, output, input, input, DlssNative.Image.NONE,
                0, 0, 1, 1, 0, 1, 1, 64, 48);
            require(sr.evaluate(42, current[0], eval).ok(), "fake SR evaluate");
            fault.invokeExact(4, 1, 0); // OpRelease
            require(!sr.releaseFeature(current[0]).ok(), "release failure was hidden by wrapper");
            require(!sr.evaluate(42, current[0], eval).ok(), "retirement-only owner was evaluated");
            require(sr.releaseFeature(current[0]).ok(), "release retry lost owner");
            var optimal = new DlssNative.OptimalSettings[1];
            require(sr.queryOptimalSettings(DlssQuality.QUALITY, 128, 96, optimal).ok(), "SR release destroyed shared query state");
            if (ngx) {
                fault.invokeExact(6, 1, 0); // OpShutdown
                boolean rejected = false;
                try { sr.shutdown(); } catch (DlssNativeException expected) { rejected = true; }
                require(rejected, "checked shutdown failure hidden by FFM");
            }
            sr.shutdown();
            System.out.println(library.getFileName() + " actual FFM partial SR/RR ownership, release-only rejection, retry, shared query and checked shutdown passed");
        }
    }
}
