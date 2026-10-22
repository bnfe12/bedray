package io.github.andrexarlay.vulkancompat.compute;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.andrexarlay.bedray.api.v1.AtlasFrame;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.vulkan.VK10;

/** Render-thread lifetime guard for the borrowed, animated block atlas and its baked models. */
public final class ComputeAtlasBridge {
    private static volatile boolean reloadPending = true;
    private static boolean uploaded;
    private static long generation = 1;
    private static GpuTexture texture;
    private static GpuTextureView view;
    private static Object models;
    private static AtlasFrame frame;

    private ComputeAtlasBridge() {}

    /** Block capture for the entire asynchronous reload, including the model/atlas apply interval. */
    public static void onReloadStarted() {
        reloadPending = true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && !minecraft.isSameThread()) {
            minecraft.execute(ComputeAtlasBridge::invalidate);
        } else invalidate();
    }

    /** Called only after a successful reload has applied all resources, including the initial load. */
    public static void onReloadFinished() {
        reloadPending = false;
    }

    /** Must run before TextureAtlas closes its old view/image, even when dimensions do not change. */
    public static void onAtlasReleased() {
        uploaded = false;
        invalidate();
    }

    public static void onAtlasUploaded() {
        uploaded = true;
    }

    public static void onDeviceClose() {
        uploaded = false;
        invalidate();
    }

    private static void invalidate() {
        // Flush queued references and retire provider descriptors before forgetting borrowed handles.
        ComputeRenderBridge.onMaterialsInvalidated();
        texture = null;
        view = null;
        models = null;
        frame = null;
        generation = Math.incrementExact(generation);
    }

    /** Null keeps the normal renderer while atlas and models are unavailable or being replaced. */
    static AtlasFrame current() {
        Minecraft minecraft = Minecraft.getInstance();
        if (reloadPending || !uploaded || minecraft == null || !minecraft.isSameThread()
                || minecraft.gui.overlay() instanceof LoadingOverlay) return null;
        var device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null) return null;
        var atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (!(atlas instanceof TextureAtlas)) return null;
        GpuTexture nextTexture = atlas.getTexture();
        GpuTextureView nextView = atlas.getTextureView();
        Object nextModels = minecraft.getModelManager().getBlockStateModelSet();
        if (nextTexture == null || nextView == null || nextModels == null
                || nextTexture.isClosed() || nextView.isClosed()) return null;
        long imageView = WorldTargetManager.extractVkImageView(nextView);
        int width = nextTexture.getWidth(0), height = nextTexture.getHeight(0);
        int format = com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(nextTexture.getFormat());
        if (imageView == 0 || width <= 0 || height <= 0 || format != VK10.VK_FORMAT_R8G8B8A8_UNORM) return null;
        long address = device.vkDevice().address();
        if (frame != null && (texture != nextTexture || view != nextView || models != nextModels
                || frame.deviceAddress() != address || frame.imageView() != imageView)) invalidate();
        if (frame == null) {
            texture = nextTexture;
            view = nextView;
            models = nextModels;
            frame = new AtlasFrame(generation, address, imageView, VK10.VK_IMAGE_LAYOUT_GENERAL,
                format, width, height, AtlasFrame.ColorEncoding.SRGB_IN_UNORM);
        }
        return frame;
    }
}
