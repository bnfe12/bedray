package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import io.github.andrexarlay.vulkancompat.shaderhost.IrisShaderHost;
import io.github.andrexarlay.vulkancompat.shaderhost.NativeShaderHost;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostKind;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostSelector;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostState;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostStatus;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHosts;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReportCache;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L2 JUnit tests for {@link IrisFrameHook} (I3.5 game wiring): host gating (the native path must stay untouched),
 * the guard chain in front of the GPU (no target, no Vulkan handles, no device), session teardown, and the
 * per-frame {@code [VKB-IRIS]} log contract. GPU recording itself is covered by {@link IrisPostExecutorTest}
 * and by the in-game autotest (L4/L5), not here.
 */
class IrisFrameHookTest {

    private IrisShaderHost irisHost;
    private ShaderHostSelector selector;
    private CameraRenderState camera;

    @BeforeEach
    void setUp() throws IOException {
        IrisRenderBridge.teardown();
        IrisRenderBridge.resetTiming();

        irisHost = new IrisShaderHost(new PackReportCache(ScanLimits.defaults()));
        irisHost.setStatusForTesting(ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.READY, "ready"));
        irisHost.setActiveGraphForTesting(IrisPassGraph.build(findTinyTestpack(), ScanLimits.defaults()).graph());
        selector = new ShaderHostSelector(new NativeShaderHost(), irisHost);
        camera = new CameraRenderState();
    }

    @AfterEach
    void tearDown() {
        IrisRenderBridge.teardown();
        IrisRenderBridge.resetTiming();
        ShaderHosts.resetForTesting();
    }

    private void selectNative() {
        ShaderHostStatus nat = ShaderHostStatus.of(ShaderHostKind.NATIVE, ShaderHostState.READY, "native ready");
        ShaderHosts.setSelectorForTesting(selector,
            new ShaderHostSelector.Selection(ShaderHostKind.NATIVE, nat, ShaderHostKind.NATIVE, nat));
    }

    private void selectIris() {
        ShaderHostStatus st = ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.READY, "ready");
        ShaderHosts.setSelectorForTesting(selector,
            new ShaderHostSelector.Selection(ShaderHostKind.IRIS, st, ShaderHostKind.IRIS, st));
    }

    // --- Timing gating ---

    @Test
    void onFrameBeginDoesNotAdvanceTimingOnNativePath() {
        selectNative();
        IrisFrameHook.onFrameBegin();
        IrisFrameHook.onFrameBegin();
        assertEquals(0, IrisRenderBridge.frameCounter(), "native path must not touch Iris timing");
        assertEquals(0.0f, IrisRenderBridge.frameTimeCounter());
    }

    @Test
    void onFrameBeginAdvancesTimingOncePerFrameOnIrisPath() {
        selectIris();
        IrisFrameHook.onFrameBegin();
        IrisFrameHook.onFrameBegin();
        assertEquals(2, IrisRenderBridge.frameCounter());
        assertTrue(IrisRenderBridge.frameTimeCounter() > 0.0f);
    }

    // --- Render gating ---

    @Test
    void renderIsNoOpWithoutSelection() {
        ShaderHosts.resetForTesting();
        assertFalse(IrisFrameHook.renderWorldPost(null, camera, null));
        assertFalse(IrisRenderBridge.isInitialized());
    }

    @Test
    void renderIsNoOpOnNativePath() {
        selectNative();
        assertFalse(IrisFrameHook.renderWorldPost(fakeTarget(0, 0, 0), camera, null));
        assertFalse(IrisRenderBridge.isInitialized(), "native path must never create the Iris executor");
    }

    @Test
    void renderIsFalseWhenHostNotReady() {
        irisHost.setStatusForTesting(ShaderHostStatus.of(ShaderHostKind.IRIS, ShaderHostState.COMPILE_FAILED, "boom"));
        selectIris();
        assertFalse(IrisFrameHook.renderWorldPost(fakeTarget(0, 0, 0), camera, null));
        assertFalse(IrisRenderBridge.isInitialized());
    }

    @Test
    void renderIsFalseWhenDisabledForSession() {
        selectIris();
        selector.disableForSession(ShaderHostKind.IRIS, "test failure");
        assertFalse(IrisFrameHook.renderWorldPost(fakeTarget(0, 0, 0), camera, null));
        assertFalse(IrisRenderBridge.isInitialized());
    }

    @Test
    void renderIsFalseWithNullTarget() {
        selectIris();
        assertFalse(IrisFrameHook.renderWorldPost(null, camera, null));
        assertFalse(IrisRenderBridge.isInitialized());
    }

    @Test
    void renderIsFalseWithoutVkHandles() {
        selectIris();
        assertFalse(IrisFrameHook.renderWorldPost(fakeTarget(0, 0, 0), camera, null),
            "a target without Vulkan textures must not reach the executor");
        assertFalse(IrisRenderBridge.isInitialized());
    }

    @Test
    void renderIsFalseWithoutDevice() {
        selectIris();
        RenderTarget target = fakeTarget(1001L, 1002L, 1003L);
        assumeTrue(target != null, "Unsafe-allocated Vulkan textures required");
        assertFalse(IrisFrameHook.renderWorldPost(target, camera, null),
            "without a Vulkan device (L2) the hook must report recorded=false, not crash");
        assertFalse(IrisRenderBridge.isInitialized());
    }

    // --- Teardown ---

    @Test
    void teardownClosesExecutorAndResetsTiming() {
        selectIris();
        IrisRenderBridge.setExecutorForTesting(IrisPostExecutor.createForTesting(), "tiny-testpack");
        IrisFrameHook.onFrameBegin();
        assertTrue(IrisRenderBridge.isInitialized());
        assertTrue(IrisRenderBridge.frameCounter() > 0);

        IrisFrameHook.teardown("world_unload");

        assertFalse(IrisRenderBridge.isInitialized(), "world unload must not leave stale GPU images");
        assertNull(IrisRenderBridge.activePackName());
        assertEquals(0, IrisRenderBridge.frameCounter());
        assertEquals(0.0f, IrisRenderBridge.frameTimeCounter());
    }

    @Test
    void teardownIsIdempotentWithoutExecutor() {
        selectIris();
        assertDoesNotThrow(() -> IrisFrameHook.teardown("resource_reload"));
        assertDoesNotThrow(() -> IrisFrameHook.teardown("resource_reload"));
        assertFalse(IrisRenderBridge.isInitialized());
    }

    // --- Log contract ---

    @Test
    void logLineMatchesVerifierContract() {
        assertEquals("[VKB-IRIS] requested=iris active=iris state=READY passes=2 recorded=true",
            IrisFrameHook.logLine(ShaderHostKind.IRIS, ShaderHostKind.IRIS, ShaderHostState.READY, 2, true, null));
        assertEquals("[VKB-IRIS] requested=iris active=iris state=READY passes=2 recorded=false reason=\"no_device\"",
            IrisFrameHook.logLine(ShaderHostKind.IRIS, ShaderHostKind.IRIS, ShaderHostState.READY, 2, false, "no_device"));
    }

    // --- Helpers ---

    private static Path findTinyTestpack() {
        Path p = Path.of("src", "test", "resources", "testpacks", "tiny-testpack");
        if (Files.isDirectory(p)) {
            return p;
        }
        p = Path.of("..", "testpacks", "tiny-testpack");
        if (Files.isDirectory(p)) {
            return p;
        }
        p = Path.of("testpacks", "tiny-testpack");
        if (Files.isDirectory(p)) {
            return p;
        }
        throw new IllegalStateException("tiny-testpack not found");
    }

    /** RenderTarget with Unsafe-allocated Vulkan textures carrying the given handles (0 = leave the field null). */
    private static RenderTarget fakeTarget(long colorImage, long colorView, long depthView) {
        FakeTarget t = new FakeTarget();
        t.width = 64;
        t.height = 48;
        try {
            GpuTexture color = null;
            GpuTextureView colorV = null;
            GpuTextureView depthV = null;
            if (colorImage != 0) {
                VulkanGpuTexture tex = (VulkanGpuTexture) unsafe().allocateInstance(VulkanGpuTexture.class);
                putLong(tex, VulkanGpuTexture.class, "vkImage", colorImage);
                color = tex;
            }
            if (colorView != 0) {
                VulkanGpuTextureView view = (VulkanGpuTextureView) unsafe().allocateInstance(VulkanGpuTextureView.class);
                putLong(view, VulkanGpuTextureView.class, "vkImageView", colorView);
                colorV = view;
            }
            if (depthView != 0) {
                VulkanGpuTextureView view = (VulkanGpuTextureView) unsafe().allocateInstance(VulkanGpuTextureView.class);
                putLong(view, VulkanGpuTextureView.class, "vkImageView", depthView);
                depthV = view;
            }
            t.textures(color, colorV, depthV);
        } catch (Throwable e) {
            return null;
        }
        return t;
    }

    private static final class FakeTarget extends RenderTarget {
        FakeTarget() {
            super("iris-hook-test", null, null);
        }

        void textures(GpuTexture color, GpuTextureView colorView, GpuTextureView depthView) {
            this.colorTexture = color;
            this.colorTextureView = colorView;
            this.depthTextureView = depthView;
        }
    }

    private static Unsafe unsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    private static void putLong(Object target, Class<?> owner, String field, long value) throws Exception {
        Field f = owner.getDeclaredField(field);
        unsafe().putLong(target, unsafe().objectFieldOffset(f), value);
    }
}