package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvReflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User-supplied resources of a custom RT pack beyond the fixed contract bindings: one push-constant file
 * ({@code bedray/rt.push}) and, for every extra set-0 binding of the compiled shader, a data file in the pack —
 * {@code bedray/buffer<N>.bin} for uniform/storage buffers, {@code bedray/texture<N>.png} for sampled images.
 * <p>
 * Push constants and buffer contents can be re-read from disk while the game runs (folder packs only), so a node
 * editor writes parameter bytes and the next frame picks them up without a restart. Zips load once. Texture images
 * are static for the session. Caps keep a bad file from allocating wild VRAM.
 */
final class CustomRtResources {
    private static final Logger LOG = LoggerFactory.getLogger("VulkanRtx/CustomRt");
    /** Single user buffer cap; also under vkCmdUpdateBuffer's range limits for the memset path. */
    static final long MAX_BUFFER_BYTES = 8L * 1024 * 1024;
    static final long MAX_TOTAL_BUFFER_BYTES = 16L * 1024 * 1024;
    static final int MAX_TEXTURE_DIM = 2048;
    private static final long MAX_PUSH_BYTES = 4096;

    record BufferSpec(int binding, SpirvReflection.DescriptorKind kind, Path liveFile, long size) {}
    record TextureSpec(int binding, SpirvReflection.DescriptorKind kind, int width, int height) {}

    private byte[] push;
    private final List<BufferSpec> buffers = new ArrayList<>();
    private final List<byte[]> bufferContent = new ArrayList<>();
    private final List<TextureSpec> textures = new ArrayList<>();
    private final List<byte[]> textureRgba = new ArrayList<>();
    private final Map<Path, Long> watched = new LinkedHashMap<>();
    private boolean dirty;

    private CustomRtResources() {}

    /** Builds resource specs from the reflected shader bindings and the pack's user files. Throws on a mismatch. */
    static CustomRtResources build(SpirvReflection.Module module, String root, PackView pack, Path liveRoot) {
        CustomRtResources res = new CustomRtResources();
        long total = 0;
        for (SpirvReflection.Binding b : module.bindings()) {
            int n = b.binding();
            if (b.set() != 0 || n < 5) continue;
            // binding 15 as a storage buffer is the host's world volume (MosaicWorld), not a pack file
            if (n == io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.BINDING
                && b.kind() == SpirvReflection.DescriptorKind.STORAGE_BUFFER) continue;
            switch (b.kind()) {
                case UNIFORM_BUFFER, STORAGE_BUFFER -> {
                    byte[] data = pack.read("bedray/buffer" + n + ".bin", "binding " + n + " (" + b.kind() + ")");
                    if (data.length == 0) throw new IllegalArgumentException("buffer" + n + ".bin is empty; a buffer binding needs bytes");
                    if (data.length > MAX_BUFFER_BYTES)
                        throw new IllegalArgumentException("buffer" + n + ".bin is " + data.length + " B, over the "
                            + MAX_BUFFER_BYTES + " B single-buffer cap");
                    if (b.kind() == SpirvReflection.DescriptorKind.UNIFORM_BUFFER && data.length > 65536)
                        throw new IllegalArgumentException("buffer" + n + ".bin holds " + data.length
                            + " B; a uniform buffer block cannot exceed 65536 B");
                    total += data.length;
                    if (total > MAX_TOTAL_BUFFER_BYTES)
                        throw new IllegalArgumentException("user buffers total " + total + " B, over the " + MAX_TOTAL_BUFFER_BYTES + " B cap");
                    Path live = liveRoot == null ? null : liveRoot.resolve("bedray/buffer" + n + ".bin");
                    res.buffers.add(new BufferSpec(n, b.kind(), live, data.length));
                    res.bufferContent.add(data);
                    if (live != null) res.watched.put(live, mtime(live));
                }
                case COMBINED_IMAGE_SAMPLER, SAMPLED_IMAGE -> {
                    byte[] png = pack.read("bedray/texture" + n + ".png", "binding " + n + " (" + b.kind() + ")");
                    BufferedImage img;
                    try {
                        img = ImageIO.read(new ByteArrayInputStream(png));
                    } catch (IOException e) {
                        throw new IllegalArgumentException("texture" + n + ".png is not a decodable PNG: " + e.getMessage());
                    }
                    if (img == null) throw new IllegalArgumentException("texture" + n + ".png is not a decodable PNG");
                    if (img.getWidth() > MAX_TEXTURE_DIM || img.getHeight() > MAX_TEXTURE_DIM)
                        throw new IllegalArgumentException("texture" + n + ".png is " + img.getWidth() + "x" + img.getHeight()
                            + ", over the " + MAX_TEXTURE_DIM + " px texture cap");
                    res.textures.add(new TextureSpec(n, b.kind(), img.getWidth(), img.getHeight()));
                    res.textureRgba.add(toRgba(img));
                }
                default -> throw new IllegalArgumentException("binding " + n + " kind " + b.kind()
                    + " has no pack file producer; extra bindings support uniform/storage buffers and sampled images");
            }
        }
        if (module.pushConstants()) {
            res.push = pack.read("bedray/rt.push", "the push-constant block");
            if (res.push.length > MAX_PUSH_BYTES)
                throw new IllegalArgumentException("rt.push holds " + res.push.length + " B, over the " + MAX_PUSH_BYTES + " B push-constant cap"
                    + "; every byte of the shader's push block must be covered");
            Path live = liveRoot == null ? null : liveRoot.resolve("bedray/rt.push");
            if (live != null) res.watched.put(live, mtime(live));
        }
        return res;
    }

    /** Re-reads changed user files of a folder pack; returns true when the next dispatch must see new data. */
    boolean refresh() {
        dirty = false;
        for (Map.Entry<Path, Long> e : watched.entrySet()) {
            Path file = e.getKey();
            long stamp = mtime(file);
            if (stamp == e.getValue()) continue;
            try {
                byte[] now = Files.readAllBytes(file);
                int index = indexOf(file);
                if (index >= 0) {
                        ensureSizeCap(index, now.length, file);
                    bufferContent.set(index, now);
                } else {
                    if (now.length > MAX_PUSH_BYTES)
                        throw new IllegalArgumentException("rt.push holds " + now.length + " B, over the " + MAX_PUSH_BYTES + " B cap");
                    push = now;
                }
                watched.put(file, stamp);
                dirty = true;
                LOG.info("[VKB-CUSTOMRT] live update {}", file.getFileName());
            } catch (IOException | IllegalArgumentException ex) {
                LOG.warn("[VKB-CUSTOMRT] live update of {} failed: {}", file, ex.getMessage());
                watched.put(file, stamp); // do not spin on a broken file; it is re-read on the next change
            }
        }
        return dirty;
    }

    byte[] push(long maxPushConstantsSize) {
        if (push == null) return null;
        int size = Math.min(push.length, (int) Math.min(maxPushConstantsSize, MAX_PUSH_BYTES));
        byte[] out = new byte[size];
        System.arraycopy(push, 0, out, 0, size);
        return out;
    }

    /** Total bytes of the push file, 0 when the shader has no push-constant block or no file. */
    int pushBytes() { return push == null ? 0 : push.length; }

    List<BufferSpec> bufferSpecs() { return buffers; }
    byte[] bufferContent(int i) { return bufferContent.get(i); }
    List<TextureSpec> textureSpecs() { return textures; }
    byte[] textureRgba(int i) { return textureRgba.get(i); }

    private int indexOf(Path file) {
        for (int i = 0; i < buffers.size(); i++)
            if (file.equals(buffers.get(i).liveFile())) return i;
        return -1;
    }

    /** Live re-reads keep the allocation: the file may grow only if it still fits the created buffer. */
    private void ensureSizeCap(int index, int now, Path file) {
        long cap = buffers.get(index).size();
        if (now > cap)
            throw new IllegalArgumentException(file.getFileName() + " grew to " + now + " B but the binding was created for "
                + cap + " B; restart to pick up the larger file");
    }

    private static long mtime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static byte[] toRgba(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < argb.length; i++) {
            rgba[i * 4] = (byte) (argb[i] >>> 16);
            rgba[i * 4 + 1] = (byte) (argb[i] >>> 8);
            rgba[i * 4 + 2] = (byte) argb[i];
            rgba[i * 4 + 3] = (byte) (argb[i] >>> 24);
        }
        return rgba;
    }

    /** Pack entry access shared with the bootstrap: bounded reads with a shape-checked error message. */
    interface PackView {
        byte[] read(String name, String what);
    }
}
