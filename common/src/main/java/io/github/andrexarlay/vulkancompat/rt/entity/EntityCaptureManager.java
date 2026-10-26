package io.github.andrexarlay.vulkancompat.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.andrexarlay.vulkancompat.rt.world.RtEmitters;
import io.github.andrexarlay.vulkancompat.rt.world.RtEntityGeometryManager;
import net.minecraft.client.model.Model;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Central runtime manager for entity geometry capture.
 * Captures models, items and animated parts from EntityRenderDispatcher / SubmitNodeCollection
 * into canonical EntityGeometryFrame structures.
 */
public final class EntityCaptureManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("VK-ENTITY-CAPTURE");
    private static final EntityCaptureManager INSTANCE = new EntityCaptureManager();

    public static EntityCaptureManager getInstance() {
        return INSTANCE;
    }

    /** Lifecycle state of the entity capture manager for a single frame. */
    public enum State {
        DISABLED,    // RT is off; capture is skipped
        CAPTURING,   // between beginFrame(true) and endFrame()
        PUBLISHED,   // endFrame() completed with vertices > 0; ready for GPU upload
        CONSUMED,    // markConsumed() called by RtWorld/upload pass
        EMPTY,       // endFrame() completed with 0 vertices (no entities this frame)
        FAILED       // an unrecoverable error occurred during capture
    }

    private final AtomicBoolean loggedFirstCapture = new AtomicBoolean(false);

    // Frame telemetry & counters
    private final AtomicLong hookInvocations = new AtomicLong(0);
    private final AtomicLong entitiesSeen = new AtomicLong(0);
    private final AtomicLong entitiesCaptured = new AtomicLong(0);
    private final AtomicLong totalVertices = new AtomicLong(0);
    private final AtomicLong totalIndices = new AtomicLong(0);

    // Lifecycle tracking
    private volatile State state = State.DISABLED;
    private long captureFrameId = 0;
    private long renderFrameId = -1;
    private long latencyFrames = 0;
    private long lastFrameVertices = 0;
    private long lastFrameIndices = 0;
    private String lastEntityType = "none";
    private String lastError = "none";

    private long currentFrameId = 0;
    private long geometryGeneration = 0;


    // Active frame staging
    private final List<EntityGeometryFrame.CapturedEntity> stagedEntities = new ArrayList<>();
    private final List<EntitySceneRecord> stagedSceneRecords = new ArrayList<>();
    private final List<EntityMaterialRecord> stagedMaterialRecords = new ArrayList<>();
    private final CapturedVertexConsumer frameConsumer = new CapturedVertexConsumer();

    CapturedVertexConsumer frameConsumer() {
        return frameConsumer;
    }

    // Quad emitters of emissive items (RtEmitters record layout, positions relative to the camera, world axes)
    private float[] worldEmitters = new float[RtEmitters.FLOATS * 64];
    private int worldEmitterCount;
    /** The emitters of the frame that was closed by {@link #endFrame()} (what the RT pass of that frame lights). */
    private float[] publishedEmitters = new float[0];
    private int publishedEmitterCount;
    private static final int MAX_PARTICLE_EMITTERS_PER_FRAME = 256;
    private int particleEmitterCount;
    private int publishedParticleEmitterCount;
    private final float[] particlePositions = new float[12];
    private final float[] particleUvs = new float[8];
    private final int[] particleColors = new int[4];
    private final float[] particleRecord = new float[RtEmitters.FLOATS];
    private final Vector4f quadScratch = new Vector4f();
    private final ParticleVertexConsumer particleVertexConsumer = new ParticleVertexConsumer();
    private final AtomicBoolean loggedParticleCaptureFailure = new AtomicBoolean(false);
    // First-person hand pass (GameRenderer.renderItemInHand, after the RT pass of the frame): the item in the hand as emitter
    // quads in VIEW space (camera axes), so the next frame can place them with its own camera: the hand follows the view
    private boolean handPass;
    /** RT is on for the current frame (beginFrame(frameId, true)): the hand pass is only followed then (no work, no raster change with RT off). */
    private boolean frameEnabled;
    private final Matrix4f handViewRotation = new Matrix4f();
    private float[] handEmitters = new float[RtEmitters.FLOATS * 16];
    private int handEmitterCount;
    private long frameSeq;
    private long handSeq = Long.MIN_VALUE / 2;

    // Active entity staging
    private boolean insideEntity = false;
    private int curEntityId;
    private String curEntityType;
    private Matrix4f curTransform = new Matrix4f().identity();
    private int curFlags;
    private int entityStartVertex = 0;
    private int entityStartIndex = 0;

    private EntityCaptureManager() {
    }

    /** Disjoint render subsets for the first-person hand item; null means the hand pass is not active. */
    public record HandItemSplit(ItemQuads material, ItemQuads emissive) {
    }

    /** True between beginFrame (render() HEAD, RT active) and endFrame (RT pass of the frame): only the level's submits land here. */
    private boolean open;
    private final AtomicLong captureFailures = new AtomicLong(0);

    /**
     * Starts capturing the entity / block entity / item submits of a frame. {@code enabled} = ray tracing will consume them;
     * otherwise nothing is recorded (no CPU cost with RT off).
     */
    public synchronized void beginFrame(long frameId, boolean enabled) {
        beginFrame(frameId);
        this.frameEnabled = enabled;
        if (enabled) {
            EntityTextureAtlas.INSTANCE.checkReload();
            this.state = State.CAPTURING;
        } else {
            this.state = State.DISABLED;
        }
        this.open = enabled;
    }

    public synchronized boolean isOpen() {
        return open;
    }

    public synchronized void beginFrame(long frameId) {
        OffscreenBlockEntities.beginFrame();
        this.open = true;
        this.currentFrameId = frameId;
        this.captureFrameId = frameId;
        this.renderFrameId = -1;
        stagedEntities.clear();
        stagedSceneRecords.clear();
        stagedMaterialRecords.clear();
        frameConsumer.clear();
        insideEntity = false;
        worldEmitterCount = 0;
        particleEmitterCount = 0;
        selfAvatarVerts = 0;
        selfAvatarTriStart = 0;
        selfAvatarTriCount = 0;
        frameSeq++;
    }

    public synchronized void beginEntity(int entityId, String type, Matrix4f transform, int flags) {
        hookInvocations.incrementAndGet();
        entitiesSeen.incrementAndGet();

        this.insideEntity = true;
        this.curEntityId = entityId;
        this.curEntityType = type != null ? type : "minecraft:unknown";
        this.lastEntityType = this.curEntityType;
        this.curTransform = transform != null ? new Matrix4f(transform) : new Matrix4f().identity();

        // For chests, signs, items, and non-living entities, flag 0x40 must be STRICTLY 0
        boolean isNonLiving = (flags & (EntityGeometryFrame.CapturedEntity.FLAG_BLOCK_ENTITY | EntityGeometryFrame.CapturedEntity.FLAG_ITEM)) != 0
                || (this.curEntityType.startsWith("be:") || this.curEntityType.contains("chest") || this.curEntityType.contains("sign")
                || this.curEntityType.equals("block_entity") || this.curEntityType.equals("item") || this.curEntityType.equals("text")
                || this.curEntityType.equals("breaking_block"));
        if (isNonLiving) {
            flags &= ~0x40;
            frameConsumer.setAllowHurt(false);
        } else {
            frameConsumer.setAllowHurt(true);
        }
        this.curFlags = flags;
        frameConsumer.setFlags(flags);

        frameConsumer.flushRemaining();
        this.entityStartVertex = frameConsumer.getVertexCount();
        this.entityStartIndex = frameConsumer.getIndexCount();
    }

    @SuppressWarnings("unchecked")
    public synchronized <S> void captureModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light, int overlay, int color) {
        captureModel(model, state, poseStack, renderType, light, overlay, color, null);
    }

    /** Sampler0 texture of a RenderType (accessors, javap 26.3), null if none. */
    static Identifier textureOf(RenderType renderType) {
        if (!(renderType instanceof RenderTypeAccess rta) || !((Object) rta.vkb$state() instanceof RenderSetupAccess rsa)) {
            return null;
        }
        java.util.Map<?, ?> textures = rsa.vkb$textures();
        if (textures == null || textures.isEmpty()) {
            return null;
        }
        Object b = textures.containsKey("Sampler0") ? textures.get("Sampler0") : textures.values().iterator().next();
        return b instanceof TextureBindingAccess tba ? tba.vkb$location() : null;
    }

    @SuppressWarnings("unchecked")
    public synchronized <S> void captureModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light, int overlay, int color,
                                              net.minecraft.client.renderer.texture.UvMapping uvMapping) {
        if (!open || model == null || poseStack == null) {
            return;
        }
        // block entities (chests, signs, ...) are submitted by BlockEntityRenderDispatcher, outside EntityRenderDispatcher.submit
        boolean implicit = !insideEntity;
        if (implicit) {
            beginEntity(-1, "block_entity", poseStack.last().pose(), EntityGeometryFrame.CapturedEntity.FLAG_BLOCK_ENTITY);
        }
        try {
            // vanilla ModelFeatureRenderer: setupAnim(state) then renderToBuffer (javap 26.3); the model instance is shared
            ((Model<S>) model).setupAnim(state);
            // texture: an atlas sprite UvMapping (chests, signs, ...) -> the sprite PNG, model UVs are sprite-local (we do not
            // wrap); otherwise the Sampler0 texture of the RenderType (entity PNG). Atlas textures without a sprite -> white.
            int slot = 0;
            if (uvMapping instanceof net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
                slot = EntityTextureAtlas.INSTANCE.spriteSlot(sprite.contents());
            } else {
                Identifier tex = textureOf(renderType);
                if (tex != null) {
                    slot = EntityTextureAtlas.INSTANCE.slot(tex, 0, 0);
                }
            }
            if (slot == 0) {
                String rtStr = renderType != null ? renderType.toString() : "";
                if (rtStr.contains("trim") || rtStr.contains("paletted") || rtStr.contains("glint") || rtStr.contains("decal")) {
                    return;
                }
            }
            int modelStartVert = frameConsumer.getVertexCount();
            frameConsumer.setTexture(slot, null);
            model.renderToBuffer(poseStack, frameConsumer, light, overlay, color);
            frameConsumer.flushRemaining();
            int modelEndVert = frameConsumer.getVertexCount();
            if (slot > 0 && EntityTextureAtlas.INSTANCE.hasEmission(slot) && modelEndVert > modelStartVert) {
                stageCapturedQuadsEmitters(slot, modelStartVert, modelEndVert, 1.8f);
            }
        } catch (RuntimeException e) {
            // counted and logged (first one with stack trace); the entity is missing from the RT scene, the raster image is unaffected
            if (captureFailures.getAndIncrement() == 0) {
                LOGGER.error("[VK-ENTITY-CAPTURE] model capture failed type={} model={}", curEntityType, model.getClass().getName(), e);
            }
        } finally {
            // Atlas-layer skips and capture failures must not leak the implicit entity into the next submission.
            try {
                frameConsumer.setTexture(0, null);
            } finally {
                if (implicit) {
                    endEntity();
                }
            }
        }
    }

    /**
     * @return true when this is an emissive item of the first-person hand pass: the caller (MixinSubmitNodeCollection) then draws
     *         it with full light, the glow of the item itself (its pixels also light the scene through the emitter quads)
     */
    public synchronized boolean captureItem(PoseStack poseStack, ItemQuads itemQuads, int[] tintColors, int light, int overlay) {
        if (handPass && !open && poseStack != null && itemQuads != null && !itemQuads.isEmpty()) {
            // the item in the first-person hand: not world geometry (the raster hand pass draws it), only its glowing pixels
            // light the scene: emitter quads in view space
            HandItemSplit split = captureHandItem(poseStack, itemQuads, tintColors);
            return split != null && !split.emissive().isEmpty();
        }
        if (!open || itemQuads == null || itemQuads.isEmpty()) {
            return false;
        }
        boolean implicit = !insideEntity;
        if (implicit) {
            beginEntity(-1, "item", poseStack != null ? poseStack.last().pose() : null, EntityGeometryFrame.CapturedEntity.FLAG_ITEM);
        }

        PoseStack.Pose pose = poseStack != null ? poseStack.last() : null;
        for (BakedQuad quad : itemQuads.all()) {
            if (quad == null) continue;
            int quadTint = itemQuadTintColor(quad.materialInfo(), tintColors);
            // BakedQuad in 26.3 provides 4 vertices: positions 0..3 and packedUV 0..3
            var sprite = quad.materialInfo() != null ? quad.materialInfo().sprite() : null;
            if (sprite != null) {
                frameConsumer.setTexture(EntityTextureAtlas.INSTANCE.spriteSlot(sprite.contents().name(), sprite.contents().width(),
                    sprite.contents().height()), new float[] {sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1()});
            } else {
                frameConsumer.setTexture(0, null);
            }
            if (pose != null) {
                stageQuadEmitter(pose, quad, quadTint);
            }
            for (int i = 0; i < 4; i++) {
                Vector3fc pos = quad.position(i);
                long uv = quad.packedUV(i);
                // UVPair.pack: u in the high 32 bits, v in the low 32 bits (javap -c 26.3); the old code had them swapped
                float u = net.minecraft.client.model.geom.builders.UVPair.unpackU(uv);
                float v = net.minecraft.client.model.geom.builders.UVPair.unpackV(uv);

                if (pose != null) {
                    frameConsumer.addVertex(pose, pos.x(), pos.y(), pos.z());
                } else {
                    frameConsumer.addVertex(pos.x(), pos.y(), pos.z());
                }
                frameConsumer.setColor(quadTint);
                frameConsumer.setUv(u, v);
                frameConsumer.setUv2(light & 0xFFFF, (light >>> 16) & 0xFFFF);
                if (pose != null) {
                    frameConsumer.setNormal(pose, 0f, 1f, 0f);
                } else {
                    frameConsumer.setNormal(0f, 1f, 0f);
                }
            }
        }
        frameConsumer.setTexture(0, null);
        if (implicit) {
            endEntity();
        }
        return false;
    }

    /**
     * Captures the first-person hand item's emissive NEE quads and returns a disjoint raster split. The base subset keeps
     * the original packed light; the emissive subset is submitted separately at full light by the caller. Returns null
     * outside the hand pass, or a split whose emissive set can be empty when the hand item has no emissive quads.
     *
     * <p>Eligibility is exactly {@link #buildItemEmitter}: a quad is selected only if its material sprite is in the entity
     * atlas and its UV rectangle covers emissive texels. The selection is quad-granular; mixed emissive/non-emissive texels
     * within one quad remain a known limitation.</p>
     */
    public synchronized HandItemSplit captureHandItem(PoseStack poseStack, ItemQuads itemQuads, int[] tintColors) {
        if (!handPass || open || poseStack == null || itemQuads == null || itemQuads.isEmpty()) {
            return null;
        }
        Set<BakedQuad> emissiveSet = Collections.newSetFromMap(new IdentityHashMap<>());
        stageHandEmitters(poseStack.last(), itemQuads, tintColors, emissiveSet);
        ItemQuads emissive = filterItemQuads(itemQuads, emissiveSet::contains);
        ItemQuads material = filterItemQuads(itemQuads, quad -> !emissiveSet.contains(quad));
        return new HandItemSplit(material, emissive);
    }

    /** Filters each ItemQuads list independently so the original solid/translucent classification and ordering survive. */
    static ItemQuads filterItemQuads(ItemQuads source, Predicate<BakedQuad> include) {
        if (source == null || source.isEmpty()) {
            return ItemQuads.EMPTY;
        }
        List<BakedQuad> all = filterQuads(source.all(), include);
        if (all.isEmpty()) {
            return ItemQuads.EMPTY;
        }
        return new ItemQuads(all, filterQuads(source.solid(), include), filterQuads(source.translucent(), include));
    }

    private static List<BakedQuad> filterQuads(List<BakedQuad> source, Predicate<BakedQuad> include) {
        List<BakedQuad> result = new ArrayList<>(source.size());
        for (BakedQuad quad : source) {
            if (include.test(quad)) {
                result.add(quad);
            }
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    /**
     * Matches ItemFeatureRenderer.getLayerColorSafe: tint only material-info quads with an in-range tint index;
     * un-tinted and missing/out-of-range palette entries use opaque white so they leave the source texture unchanged.
     */
    static int itemQuadTintColor(BakedQuad.MaterialInfo materialInfo, int[] tintColors) {
        if (materialInfo == null || !materialInfo.isTinted()) {
            return 0xFFFFFFFF;
        }
        int tintIndex = materialInfo.tintIndex();
        if (tintColors == null || tintIndex < 0 || tintIndex >= tintColors.length) {
            return 0xFFFFFFFF;
        }
        return tintColors[tintIndex];
    }

    /**
     * Emitter quad of one item quad when its texture has emissive texels ({@link EntityTextureAtlas}): the corners go through
     * the pose like the geometry, the UVs are sprite-local (as in the vertex stream), the record is built by
     * {@link RtEmitters#buildQuadRecord} over the emission codes of the slot. {@code tintArgb} is stored alongside the record
     * for the NEE radiance lookup. Items are always two-sided sources (flat sprites
     * have no consistent winding), the entity occludes its own back faces for shadow rays.
     * {@code hand}: the record goes to the hand list (positions converted to view space by the caller).
     */
    private boolean buildItemEmitter(PoseStack.Pose pose, BakedQuad quad, int tintArgb, float[] record) {
        var sprite = quad.materialInfo() != null ? quad.materialInfo().sprite() : null;
        if (sprite == null) {
            return false;
        }
        int slot = EntityTextureAtlas.INSTANCE.spriteSlot(sprite.contents().name(), sprite.contents().width(), sprite.contents().height());
        if (!EntityTextureAtlas.INSTANCE.hasEmission(slot)) {
            return false;
        }
        float[] pos = new float[12];
        float[] uv = new float[8];
        float du = Math.max(1e-6f, sprite.getU1() - sprite.getU0());
        float dv = Math.max(1e-6f, sprite.getV1() - sprite.getV0());
        Vector4f tmp = new Vector4f();
        for (int i = 0; i < 4; i++) {
            Vector3fc p = quad.position(i);
            tmp.set(p.x(), p.y(), p.z(), 1.0f).mul(pose.pose());
            pos[i * 3] = tmp.x;
            pos[i * 3 + 1] = tmp.y;
            pos[i * 3 + 2] = tmp.z;
            long packed = quad.packedUV(i);
            uv[i * 2] = (net.minecraft.client.model.geom.builders.UVPair.unpackU(packed) - sprite.getU0()) / du;
            uv[i * 2 + 1] = (net.minecraft.client.model.geom.builders.UVPair.unpackV(packed) - sprite.getV0()) / dv;
        }
        if (!RtEmitters.buildQuadRecord(record, 0, pos, 0, uv, 0, EntityTextureAtlas.INSTANCE.texels(slot), true)) {
            return false;
        }
        record[15] = slot + 1;   // uvc.w: entity atlas slot + 1 (0 = block atlas), see sampleEmitters in rt_common.glsl
        record[11] = 1.0f;       // two-sided
        writeItemEmitterTint(record, tintArgb);
        return true;
    }

    static void writeItemEmitterTint(float[] record, int tintArgb) {
        int o = RtEmitters.TINT_OFFSET;
        record[o] = ((tintArgb >>> 16) & 255) / 255f;
        record[o + 1] = ((tintArgb >>> 8) & 255) / 255f;
        record[o + 2] = (tintArgb & 255) / 255f;
        record[o + 3] = ((tintArgb >>> 24) & 255) / 255f;
    }

    private void stageQuadEmitter(PoseStack.Pose pose, BakedQuad quad, int tintArgb) {
        float[] rec = new float[RtEmitters.FLOATS];
        if (!buildItemEmitter(pose, quad, tintArgb, rec)) {
            return;
        }
        if ((worldEmitterCount + 1) * RtEmitters.FLOATS > worldEmitters.length) {
            worldEmitters = java.util.Arrays.copyOf(worldEmitters, worldEmitters.length * 2);
        }
        System.arraycopy(rec, 0, worldEmitters, worldEmitterCount * RtEmitters.FLOATS, RtEmitters.FLOATS);
        worldEmitterCount++;
    }

    /** Opens the first-person hand pass ({@code GameRenderer.renderItemInHand}); {@code viewRotation} = camera view rotation. */
    public synchronized void beginHandPass(Matrix4f viewRotation) {
        handPass = frameEnabled;
        handViewRotation.set(viewRotation);
        handEmitterCount = 0;
    }

    public synchronized void endHandPass() {
        if (handPass) {
            handSeq = frameSeq;
        }
        handPass = false;
    }

    /** True while GameRenderer.renderItemInHand submits the arm and the held item. */
    public synchronized boolean inHandPass() {
        return handPass;
    }

    /**
     * Adds the local player's own model to this frame's entity geometry: under a first-person camera the raster pass never
     * draws it, so a ray reflected by water or glass would hit nothing and the player would not see themselves. The state is
     * the one the game already extracted ({@code PlayerRenderState.avatarRenderState}); it is re-submitted through the vanilla
     * dispatcher with {@link RtCaptureCollector}, which keeps the model geometry and drops everything else, so the pose,
     * rotations and equipment layers stay the game's own. Third person is skipped - there the entity capture already has it.
     *
     * @return true when model geometry was captured
     */
    public synchronized boolean captureSelfAvatar(net.minecraft.client.renderer.state.level.CameraRenderState camera,
                                                  net.minecraft.client.renderer.state.level.PlayerRenderState player) {
        selfAvatarVerts = 0;
        net.minecraft.client.renderer.entity.state.AvatarRenderState avatar =
            player != null && player.hasPlayer ? player.avatarRenderState : null;
        // Sleeping in first person is the one case where vanilla does draw the own body (lying in bed): capturing the
        // standing avatar on top of it would put a second player into the same reflections.
        boolean usable = avatar != null && avatar.skin != null && !avatar.isInvisible && !avatar.isSpectator
            && camera != null && camera.pos != null
            && (camera.entityRenderState == null || !camera.entityRenderState.isSleeping);
        if (!SelfAvatarGate.shouldCapture(open, io.github.andrexarlay.vulkancompat.rt.world.RtConfig.SELF_AVATAR,
            camera != null && camera.isFirstPerson, usable)) {
            return false;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher =
            mc != null ? mc.getEntityRenderDispatcher() : null;
        if (dispatcher == null) {
            return false;
        }
        int before = frameConsumer.getVertexCount();
        int beforeIndices = frameConsumer.getIndexCount();
        try {
            dispatcher.submit(avatar, camera, avatar.x - camera.pos.x, avatar.y - camera.pos.y, avatar.z - camera.pos.z,
                new PoseStack(), RtCaptureCollector.INSTANCE);
        } catch (RuntimeException e) {
            if (selfAvatarFailures.getAndIncrement() == 0) {
                LOGGER.error("[VK-RT] self avatar capture failed", e);
            }
            endEntity();
        }
        selfAvatarVerts = frameConsumer.getVertexCount() - before;
        // The body is captured as one contiguous run of the shared index buffer the entity BLAS is built from, so the shader
        // can name its triangles. Geometry appended to the frame afterwards (the bridge instances of endFrame) is simply not
        // part of the run and stays visible to the primary rays.
        int capturedIndices = frameConsumer.getIndexCount() - beforeIndices;
        selfAvatarTriStart = beforeIndices / 3;
        selfAvatarTriCount = capturedIndices / 3;
        return selfAvatarVerts > 0;
    }

    /** First triangle of the local player's captured body in this frame's entity index buffer. */
    public synchronized int selfAvatarTriStart() {
        return selfAvatarTriStart;
    }

    /** Triangles of the local player's captured body this frame (0 when it was not captured). */
    public synchronized int selfAvatarTriCount() {
        return selfAvatarTriCount;
    }

    /** Vertices of the local player's model captured in the last frame (0 when it was not captured). */
    public synchronized int selfAvatarVertices() {
        return selfAvatarVerts;
    }

    private int selfAvatarVerts;
    private int selfAvatarTriStart;
    private int selfAvatarTriCount;
    private final java.util.concurrent.atomic.AtomicLong selfAvatarFailures = new java.util.concurrent.atomic.AtomicLong();

    private int handDiagLines;

    /**
     * Diagnostic for the first-person pass: logs what the hand and the held item are submitted with, at most eight lines per
     * session. The arm is drawn by vanilla {@code SubmitNodeCollector.submitModelPart} with
     * {@code RenderTypes.entityTranslucent(skin)}, so the render type names the texture and the pose tells the scale.
     */
    public synchronized void noteHandSubmit(String kind, String detail, RenderType renderType, int light, int overlay, PoseStack poseStack) {
        if (handDiagLines >= 8) {
            return;
        }
        handDiagLines++;
        String pose = "none";
        try {
            if (poseStack != null && !poseStack.isEmpty()) {
                Vector3f t = poseStack.last().pose().getTranslation(new Vector3f());
                pose = String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", t.x(), t.y(), t.z());
            }
        } catch (RuntimeException ignored) {
        }
        LOGGER.info("[VK-HAND-DIAG] kind={} {} renderType={} texture={} light={} overlay={} pose={}", kind, detail,
            renderType != null ? renderType.toString() : "null", renderType != null ? String.valueOf(textureOf(renderType)) : "null",
            Integer.toHexString(light), Integer.toHexString(overlay), pose);
    }

    private void stageHandEmitters(PoseStack.Pose pose, ItemQuads itemQuads, int[] tintColors, Set<BakedQuad> emissiveQuads) {
        for (BakedQuad quad : itemQuads.all()) {
            if (quad == null) {
                continue;
            }
            float[] rec = new float[RtEmitters.FLOATS];
            int quadTint = itemQuadTintColor(quad.materialInfo(), tintColors);
            if (!buildItemEmitter(pose, quad, quadTint, rec)) {
                continue;
            }
            emissiveQuads.add(quad);
            // world axes (camera relative) -> view space: p0 is a point, the edges are vectors; both are only rotated
            Vector4f v = new Vector4f();
            for (int k : new int[] {0, 4, 8}) {
                v.set(rec[k], rec[k + 1], rec[k + 2], 0.0f).mul(handViewRotation);
                rec[k] = v.x;
                rec[k + 1] = v.y;
                rec[k + 2] = v.z;
            }
            if ((handEmitterCount + 1) * RtEmitters.FLOATS > handEmitters.length) {
                handEmitters = java.util.Arrays.copyOf(handEmitters, handEmitters.length * 2);
            }
            System.arraycopy(rec, 0, handEmitters, handEmitterCount * RtEmitters.FLOATS, RtEmitters.FLOATS);
            handEmitterCount++;
        }
    }

    /**
     * The emitter quads for the RT pass of this frame: the emissive item quads of this frame's entity capture (dropped items,
     * items of mobs and of the player in third person, item frames) plus the item of the first-person hand of the PREVIOUS frame
     * (the hand pass runs after the RT pass), placed with the camera of this frame. Records of {@link RtEmitters#FLOATS}
     * floats, positions relative to the camera; empty array when none.
     *
     * @param viewRotation view rotation matrix of this frame's camera (CameraRenderState.viewRotationMatrix)
     */
    public synchronized float[] emitterQuadsForFrame(Matrix4f viewRotation) {
        int hand = (frameSeq - handSeq <= 1 && viewRotation != null) ? handEmitterCount : 0;
        int total = publishedEmitterCount + hand;
        if (total == 0) {
            return new float[0];
        }
        float[] out = new float[total * RtEmitters.FLOATS];
        System.arraycopy(publishedEmitters, 0, out, 0, publishedEmitterCount * RtEmitters.FLOATS);
        if (hand > 0) {
            Matrix4f inv = new Matrix4f(viewRotation).invert();
            Vector4f v = new Vector4f();
            for (int i = 0; i < hand; i++) {
                int src = i * RtEmitters.FLOATS;
                int dst = (publishedEmitterCount + i) * RtEmitters.FLOATS;
                System.arraycopy(handEmitters, src, out, dst, RtEmitters.FLOATS);
                for (int k : new int[] {0, 4, 8}) {
                    v.set(out[dst + k], out[dst + k + 1], out[dst + k + 2], 0.0f).mul(inv);
                    out[dst + k] = v.x;
                    out[dst + k + 1] = v.y;
                    out[dst + k + 2] = v.z;
                }
            }
        }
        return out;
    }

    /** Emitter quads of the entity capture of the last closed frame, and of the hand (for the autotest log). */
    public synchronized String emitterTelemetry() {
        return "world=" + publishedEmitterCount + ",particles=" + publishedParticleEmitterCount + ",hand=" + handEmitterCount
            + ",self_avatar=" + selfAvatarVerts;
    }

    /** Keep text in the RT scene; {@code -Dvkrtx.rt.text.off=true} restores the old raster-only path for A/B checks. */
    private static final boolean TEXT_OFF = Boolean.getBoolean("vkrtx.rt.text.off");
    private static final net.minecraft.resources.Identifier ASCII_FONT =
        net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "textures/font/ascii.png");
    private static final net.minecraft.resources.Identifier DEFAULT_FONT_PROVIDERS =
        net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "font/include/default.json");
    private record BitmapGlyph(Identifier texture, int column, int row, int columns, int rows) { }
    private static volatile java.util.Map<Integer, BitmapGlyph> nonAsciiGlyphs;

    /** Bitmap glyphs outside the legacy 0..255 ASCII page (notably Cyrillic sign text). */
    private static java.util.Map<Integer, BitmapGlyph> nonAsciiGlyphs() {
        java.util.Map<Integer, BitmapGlyph> cached = nonAsciiGlyphs;
        if (cached != null) return cached;
        synchronized (EntityCaptureManager.class) {
            if (nonAsciiGlyphs != null) return nonAsciiGlyphs;
            java.util.Map<Integer, BitmapGlyph> glyphs = new java.util.HashMap<>();
            var resource = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(DEFAULT_FONT_PROVIDERS);
            if (resource.isPresent()) {
                try (java.io.Reader reader = new java.io.InputStreamReader(resource.get().open(), java.nio.charset.StandardCharsets.UTF_8)) {
                    var root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                    var providers = root.getAsJsonArray("providers");
                    if (providers != null) {
                        for (var element : providers) {
                            if (!element.isJsonObject()) continue;
                            var provider = element.getAsJsonObject();
                            if (!provider.has("type") || !"bitmap".equals(provider.get("type").getAsString())
                                    || !provider.has("file") || !provider.has("chars")) continue;
                            String file = provider.get("file").getAsString();
                            int separator = file.indexOf(':');
                            String namespace = separator >= 0 ? file.substring(0, separator) : "minecraft";
                            String path = separator >= 0 ? file.substring(separator + 1) : file;
                            if (!path.startsWith("textures/")) path = "textures/" + path;
                            if (!path.endsWith(".png")) path = path + ".png";
                            Identifier texture = Identifier.fromNamespaceAndPath(namespace, path);
                            var rows = provider.getAsJsonArray("chars");
                            int rowCount = rows.size();
                            for (int row = 0; row < rowCount; row++) {
                                int[] codepoints = rows.get(row).getAsString().codePoints().toArray();
                                for (int column = 0; column < codepoints.length; column++) {
                                    int codepoint = codepoints[column];
                                    if (codepoint > 255) {
                                        glyphs.put(codepoint, new BitmapGlyph(texture, column, row, codepoints.length, rowCount));
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[VK-ENTITY-CAPTURE] could not read Minecraft bitmap font providers; non-ASCII RT text is unavailable", e);
                }
            }
            nonAsciiGlyphs = java.util.Map.copyOf(glyphs);
            return nonAsciiGlyphs;
        }
    }

    /**
     * Text (sign lines, name tags drawn as text) as RT geometry: one quad per character, textured from the vanilla ASCII
     * page or a bitmap provider in {@code font/include/default.json}. Advance = Font.width of the character. Alpha cutout
     * removes the cell background. Arguments as SubmitNodeCollection.submitText (javap -c 26.3: arg 7 = light, 8 = colour).
     */
    public synchronized void captureText(PoseStack poseStack, float x, float y, net.minecraft.util.FormattedCharSequence text,
                                         int light, int color, int outline) {
        if (!open || poseStack == null || text == null || TEXT_OFF) {
            return;
        }
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        boolean implicit = !insideEntity;
        if (implicit) {
            beginEntity(-1, "text", poseStack.last().pose(), EntityGeometryFrame.CapturedEntity.FLAG_BLOCK_ENTITY);
        }
        java.util.Map<Integer, BitmapGlyph> glyphPages = nonAsciiGlyphs();
        PoseStack.Pose pose = poseStack.last();
        int argb = (color >>> 24) == 0 ? (0xFF000000 | color) : color;
        float[] pen = {x};
        text.accept((index, style, cp) -> {
            float adv = font.width(String.valueOf(Character.toChars(cp)));
            if (cp > 32) {
                BitmapGlyph glyph = cp < 256
                    ? new BitmapGlyph(ASCII_FONT, cp & 15, cp >> 4, 16, 16)
                    : glyphPages.get(cp);
                if (glyph != null) {
                    int slot = EntityTextureAtlas.INSTANCE.slot(glyph.texture(), 0, 0);
                    // glowing text (glow ink: outline colour != 0): the glyph quad itself uses the emissive copy of the font page, so
                    // every glyph texel glows in the text colour (alpha-only emission, see EntityTextureAtlas.glowingFontSlot)
                    int glowSlot = outline != 0 ? EntityTextureAtlas.INSTANCE.glowingFontSlot(glyph.texture()) : 0;
                    frameConsumer.setTexture(glowSlot > 0 ? glowSlot : slot, null);
                    float u0 = glyph.column() / (float) glyph.columns();
                    float v0 = glyph.row() / (float) glyph.rows();
                    float du = 1f / glyph.columns();
                    float dv = 1f / glyph.rows();
                    float x0 = pen[0];
                    float x1 = x0 + 8f;
                    float y0 = y;
                    float y1 = y + 8f;
                    float[][] q = {{x0, y0, u0, v0}, {x0, y1, u0, v0 + dv}, {x1, y1, u0 + du, v0 + dv}, {x1, y0, u0 + du, v0}};
                    for (float[] v : q) {
                        frameConsumer.addVertex(pose, v[0], v[1], 0.001f);
                        frameConsumer.setColor(argb);
                        frameConsumer.setUv(v[2], v[3]);
                        frameConsumer.setUv2(light & 0xFFFF, (light >>> 16) & 0xFFFF);
                        frameConsumer.setNormal(pose, 0f, 0f, -1f);
                    }
                    // Sign renderer's 26.3 outline parameter is zero for ordinary text and the outline color for glowing
                    // text. Use a separate emissive atlas slot so ordinary text geometry never emits.
                    if (outline != 0) {
                        if (glowSlot > 0) {
                            stageTextEmitter(pose, x0, y0, x1, y1, u0, v0, du, dv, argb, glowSlot);
                        }
                    }
                }
            }
            pen[0] += adv;
            return true;
        });
        frameConsumer.setTexture(0, null);
        if (implicit) {
            endEntity();
        }
    }

    private void stageTextEmitter(PoseStack.Pose pose, float x0, float y0, float x1, float y1,
                                  float u0, float v0, float du, float dv, int argb, int slot) {
        for (int i = 0; i < 4; i++) {
            float x = (i == 0 || i == 1) ? x0 : x1;
            float y = (i == 0 || i == 3) ? y0 : y1;
            quadScratch.set(x, y, 0.001f, 1f).mul(pose.pose());
            particlePositions[i * 3] = quadScratch.x;
            particlePositions[i * 3 + 1] = quadScratch.y;
            particlePositions[i * 3 + 2] = quadScratch.z;
            particleUvs[i * 2] = (i == 0 || i == 1) ? u0 : u0 + du;
            particleUvs[i * 2 + 1] = (i == 0 || i == 3) ? v0 : v0 + dv;
            particleColors[i] = argb;
        }
        if (QuadEmitterBuilder.build(particleRecord, slot, particlePositions, particleUvs, particleColors,
                EntityTextureAtlas.INSTANCE.texels(slot))) {
            appendWorldEmitter(particleRecord);
        }
    }

    /** Bounded capture of known emissive sprites from the vanilla particle atlas; raster rendering is not intercepted. */
    public synchronized void captureParticles(net.minecraft.client.renderer.state.level.QuadParticleRenderState state) {
        if (!open || state == null || particleEmitterCount >= MAX_PARTICLE_EMITTERS_PER_FRAME) {
            return;
        }
        try {
            for (net.minecraft.client.particle.SingleQuadParticle.Layer layer : state.layers()) {
                if (particleEmitterCount >= MAX_PARTICLE_EMITTERS_PER_FRAME) {
                    break;
                }
                if (!net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_PARTICLES.equals(layer.textureAtlasLocation())) {
                    continue;
                }
                int slot = EntityTextureAtlas.INSTANCE.slot(layer.textureAtlasLocation(), 0, 0);
                if (slot == 0 || !EntityTextureAtlas.INSTANCE.hasEmission(slot)) {
                    continue;
                }
                particleVertexConsumer.begin(slot);
                state.buildLayer(layer, particleVertexConsumer);
                particleVertexConsumer.end();
            }
        } catch (RuntimeException e) {
            if (loggedParticleCaptureFailure.compareAndSet(false, true)) {
                LOGGER.warn("[VK-ENTITY-CAPTURE] emissive particle capture failed; vanilla particle rendering remains active", e);
            }
        }
    }

    private void appendParticleEmitter(int atlasSlot) {
        if (particleEmitterCount >= MAX_PARTICLE_EMITTERS_PER_FRAME
            || !QuadEmitterBuilder.build(particleRecord, atlasSlot, particlePositions, particleUvs, particleColors,
                EntityTextureAtlas.INSTANCE.texels(atlasSlot))) {
            return;
        }
        appendWorldEmitter(particleRecord);
        particleEmitterCount++;
    }

    private void appendWorldEmitter(float[] record) {
        if ((worldEmitterCount + 1) * RtEmitters.FLOATS > worldEmitters.length) {
            worldEmitters = java.util.Arrays.copyOf(worldEmitters, worldEmitters.length * 2);
        }
        System.arraycopy(record, 0, worldEmitters, worldEmitterCount * RtEmitters.FLOATS, RtEmitters.FLOATS);
        worldEmitterCount++;
    }

    /** QuadParticleRenderState writes each finalized vertex as addVertex -> UV -> ARGB color -> packed light. */
    private final class ParticleVertexConsumer implements VertexConsumer {
        private int atlasSlot;
        private int vertex;

        void begin(int atlasSlot) {
            this.atlasSlot = atlasSlot;
            this.vertex = 0;
        }

        void end() {
            vertex = 0;
            atlasSlot = 0;
        }

        private int current() { return Math.min(vertex, 3); }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (vertex < 4) {
                int i = vertex * 3;
                particlePositions[i] = x;
                particlePositions[i + 1] = y;
                particlePositions[i + 2] = z;
            }
            return this;
        }

        @Override public VertexConsumer setUv(float u, float v) {
            int i = current() * 2;
            particleUvs[i] = u;
            particleUvs[i + 1] = v;
            return this;
        }

        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            particleColors[current()] = ((alpha & 255) << 24) | ((red & 255) << 16) | ((green & 255) << 8) | (blue & 255);
            return this;
        }

        @Override public VertexConsumer setColor(int argb) {
            particleColors[current()] = argb;
            return this;
        }

        @Override public VertexConsumer setUv2(int u, int v) {
            if (++vertex == 4) {
                appendParticleEmitter(atlasSlot);
                vertex = 0;
            }
            return this;
        }

        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    private static final net.minecraft.core.Direction[] ALL_BLOCK_DIRECTIONS = new net.minecraft.core.Direction[] {
        net.minecraft.core.Direction.DOWN,
        net.minecraft.core.Direction.UP,
        net.minecraft.core.Direction.NORTH,
        net.minecraft.core.Direction.SOUTH,
        net.minecraft.core.Direction.WEST,
        net.minecraft.core.Direction.EAST,
        null
    };

    /**
     * Intercepts block breaking overlay quads (cracks / crumbling stages 0..9) so they appear in RT BLAS/TLAS.
     */
    public synchronized void captureBreakingBlock(PoseStack poseStack, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts, int stage) {
        if (!open || poseStack == null || parts == null || parts.isEmpty() || stage < 0 || stage > 9) {
            return;
        }
        Identifier tex = Identifier.fromNamespaceAndPath("minecraft", "textures/block/destroy_stage_" + stage + ".png");
        int slot = EntityTextureAtlas.INSTANCE.slot(tex, 0, 0);
        beginEntity(-1, "breaking_block", poseStack.last().pose(), EntityGeometryFrame.CapturedEntity.FLAG_BLOCK_ENTITY);
        frameConsumer.setTexture(slot, null);
        PoseStack.Pose pose = poseStack.last();
        for (net.minecraft.client.renderer.block.dispatch.BlockStateModelPart part : parts) {
            if (part == null) continue;
            for (net.minecraft.core.Direction dir : ALL_BLOCK_DIRECTIONS) {
                List<BakedQuad> quads = part.getQuads(dir);
                if (quads == null || quads.isEmpty()) continue;
                for (BakedQuad quad : quads) {
                    if (quad == null) continue;
                    float nx = 0f, ny = 1f, nz = 0f;
                    if (dir != null) {
                        nx = dir.getStepX();
                        ny = dir.getStepY();
                        nz = dir.getStepZ();
                    }
                    var sprite = quad.materialInfo() != null ? quad.materialInfo().sprite() : null;
                    float u0 = sprite != null ? sprite.getU0() : 0f;
                    float u1 = sprite != null ? sprite.getU1() : 1f;
                    float v0 = sprite != null ? sprite.getV0() : 0f;
                    float v1 = sprite != null ? sprite.getV1() : 1f;
                    float du = Math.max(1e-6f, u1 - u0);
                    float dv = Math.max(1e-6f, v1 - v0);

                    for (int i = 0; i < 4; i++) {
                        Vector3fc p = quad.position(i);
                        long packed = quad.packedUV(i);
                        float u = net.minecraft.client.model.geom.builders.UVPair.unpackU(packed);
                        float v = net.minecraft.client.model.geom.builders.UVPair.unpackV(packed);
                        if (sprite != null) {
                            u = (u - u0) / du;
                            v = (v - v0) / dv;
                        }
                        float eps = 0.002f;
                        frameConsumer.addVertex(pose, p.x() + nx * eps, p.y() + ny * eps, p.z() + nz * eps);
                        frameConsumer.setColor(0xFFFFFFFF);
                        frameConsumer.setUv(u, v);
                        frameConsumer.setUv2(15728880 & 0xFFFF, 15728880 >>> 16);
                        frameConsumer.setNormal(pose, nx, ny, nz);
                    }
                }
            }
        }
        frameConsumer.setTexture(0, null);
        endEntity();
    }

    /**
     * Stages NEE emitter records for quads captured into {@link #frameConsumer} between {@code startVert} and {@code endVert}
     * (4 vertices per quad, 10 floats per vertex: x,y,z,color,u,v,light,nx,ny,nz). Used for glowing mobs (Glow Squid, Blaze,
     * Magma Cube, Allay, Vex) and custom geometry (Activated End Portals).
     */
    private void stageCapturedQuadsEmitters(int slot, int startVert, int endVert, float gain) {
        RtEmitters.TexelEmission texels = EntityTextureAtlas.INSTANCE.texels(slot);
        List<Float> rawVerts = frameConsumer.getVertices();
        int maxVert = Math.min(endVert, rawVerts.size() / 10);
        for (int v = startVert; v + 3 < maxVert; v += 4) {
            for (int c = 0; c < 4; c++) {
                int base = (v + c) * 10;
                particlePositions[c * 3] = rawVerts.get(base);
                particlePositions[c * 3 + 1] = rawVerts.get(base + 1);
                particlePositions[c * 3 + 2] = rawVerts.get(base + 2);
                int abgr = Float.floatToRawIntBits(rawVerts.get(base + 3));
                int a = (abgr >>> 24) & 0xFF;
                int b = (abgr >>> 16) & 0xFF;
                int g = (abgr >>> 8) & 0xFF;
                int r = abgr & 0xFF;
                particleColors[c] = (a << 24) | (r << 16) | (g << 8) | b;
                particleUvs[c * 2] = rawVerts.get(base + 4);
                particleUvs[c * 2 + 1] = rawVerts.get(base + 5);
            }
            if (QuadEmitterBuilder.build(particleRecord, slot, particlePositions, particleUvs, particleColors, texels)) {
                particleRecord[7] *= gain / 2.4f;
                appendWorldEmitter(particleRecord);
            }
        }
    }

    /**
     * Captures block models submitted via {@code SubmitNodeCollector.submitBlockModel} (Item Frame wooden/glow frames,
     * falling blocks, primed TNT, minecart blocks, block displays, etc.) into the RT entity BLAS/TLAS and NEE emitter list.
     */
    public synchronized void captureBlockModel(PoseStack poseStack, RenderType renderType,
                                               List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts,
                                               int[] tintLayers, int light, int overlay) {
        if (!open || poseStack == null || parts == null || parts.isEmpty()) {
            return;
        }
        boolean implicit = !insideEntity;
        if (implicit) {
            beginEntity(-1, "block_model", poseStack.last().pose(), EntityGeometryFrame.CapturedEntity.FLAG_BLOCK_ENTITY);
        }
        PoseStack.Pose pose = poseStack.last();
        for (net.minecraft.client.renderer.block.dispatch.BlockStateModelPart part : parts) {
            if (part == null) continue;
            for (net.minecraft.core.Direction dir : ALL_BLOCK_DIRECTIONS) {
                List<BakedQuad> quads = part.getQuads(dir);
                if (quads == null || quads.isEmpty()) continue;
                for (BakedQuad quad : quads) {
                    if (quad == null) continue;
                    int quadTint = itemQuadTintColor(quad.materialInfo(), tintLayers);
                    var sprite = quad.materialInfo() != null ? quad.materialInfo().sprite() : null;
                    if (sprite != null) {
                        frameConsumer.setTexture(
                            EntityTextureAtlas.INSTANCE.spriteSlot(sprite.contents().name(), sprite.contents().width(), sprite.contents().height()),
                            new float[] {sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1()});
                    } else {
                        frameConsumer.setTexture(0, null);
                    }
                    stageQuadEmitter(pose, quad, quadTint);
                    float nx = 0f, ny = 1f, nz = 0f;
                    if (dir != null) {
                        nx = dir.getStepX();
                        ny = dir.getStepY();
                        nz = dir.getStepZ();
                    }
                    for (int i = 0; i < 4; i++) {
                        Vector3fc p = quad.position(i);
                        long packed = quad.packedUV(i);
                        float u = net.minecraft.client.model.geom.builders.UVPair.unpackU(packed);
                        float v = net.minecraft.client.model.geom.builders.UVPair.unpackV(packed);
                        frameConsumer.addVertex(pose, p.x(), p.y(), p.z());
                        frameConsumer.setColor(quadTint);
                        frameConsumer.setUv(u, v);
                        frameConsumer.setUv2(light & 0xFFFF, (light >>> 16) & 0xFFFF);
                        frameConsumer.setNormal(pose, nx, ny, nz);
                    }
                }
            }
        }
        frameConsumer.setTexture(0, null);
        if (implicit) {
            endEntity();
        }
    }

    /**
     * Captures piston moving blocks submitted via {@code SubmitNodeCollector.submitMovingBlock}.
     */
    public synchronized void captureMovingBlock(PoseStack poseStack, net.minecraft.client.renderer.block.MovingBlockRenderState state) {
        if (!open || poseStack == null || state == null || state.blockState == null || state.blockState.isAir()) {
            return;
        }
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.getModelManager() == null || mc.getModelManager().getBlockStateModelSet() == null) {
                return;
            }
            var model = mc.getModelManager().getBlockStateModelSet().get(state.blockState);
            if (model == null) {
                return;
            }
            List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts = new ArrayList<>();
            long seed = state.blockState.getSeed(state.randomSeedPos != null ? state.randomSeedPos : net.minecraft.core.BlockPos.ZERO);
            model.collectParts(net.minecraft.util.RandomSource.create(seed), parts);
            if (parts.isEmpty()) {
                return;
            }
            int light = 0xF000F0;
            if (state.lightEngine != null && state.blockPos != null) {
                int blockLight = state.lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK).getLightValue(state.blockPos);
                int skyLight = state.lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.SKY).getLightValue(state.blockPos);
                light = (skyLight << 20) | (blockLight << 4);
            }
            captureBlockModel(poseStack, null, parts, null, light, 0);
        } catch (RuntimeException ignored) {
        }
    }

    private static final Identifier END_PORTAL_TEXTURE =
        Identifier.fromNamespaceAndPath("minecraft", "textures/entity/end_portal/end_portal.png");

    /**
     * Captures custom geometry submitted via {@code SubmitNodeCollector.submitCustomGeometry}
     * (Paintings, Activated End Portals, End Gateways, Maps inside Item Frames, etc.).
     */
    public synchronized void captureCustomGeometry(PoseStack poseStack, RenderType renderType,
                                                   net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer renderer) {
        if (!open || poseStack == null || renderer == null) {
            return;
        }
        boolean implicit = !insideEntity;
        if (implicit) {
            beginEntity(-1, "custom_geometry", poseStack.last().pose(), EntityGeometryFrame.CapturedEntity.FLAG_BLOCK_ENTITY);
        }
        int savedFlags = this.curFlags;
        try {
            String rtName = renderType != null ? renderType.toString().toLowerCase(java.util.Locale.ROOT) : "";
            boolean isEndPortal = rtName.contains("end_portal") || rtName.contains("end_gateway");
            Identifier tex = isEndPortal ? END_PORTAL_TEXTURE : textureOf(renderType);
            int slot = tex != null ? EntityTextureAtlas.INSTANCE.slot(tex, 0, 0) : 0;
            frameConsumer.setTexture(slot, null);
            if (isEndPortal) {
                frameConsumer.setFlags(savedFlags | 0x80);
                frameConsumer.setColor(0xFFFFFFFF);
                frameConsumer.setUv2(240, 240);
            }
            int startVert = frameConsumer.getVertexCount();
            PoseStack.Pose pose = poseStack.last();
            renderer.render(pose, frameConsumer);
            frameConsumer.flushRemaining();
            int endVert = frameConsumer.getVertexCount();
            if (isEndPortal && endVert > startVert) {
                List<Float> rawVerts = frameConsumer.getVertices();
                int maxVert = Math.min(endVert, rawVerts.size() / 10);
                for (int v = startVert; v + 3 < maxVert; v += 4) {
                    int b0 = v * 10, b1 = (v + 1) * 10, b2 = (v + 2) * 10, b3 = (v + 3) * 10;
                    float e1x = rawVerts.get(b1) - rawVerts.get(b0);
                    float e1y = rawVerts.get(b1 + 1) - rawVerts.get(b0 + 1);
                    float e1z = rawVerts.get(b1 + 2) - rawVerts.get(b0 + 2);
                    float e2x = rawVerts.get(b2) - rawVerts.get(b0);
                    float e2y = rawVerts.get(b2 + 1) - rawVerts.get(b0 + 1);
                    float e2z = rawVerts.get(b2 + 2) - rawVerts.get(b0 + 2);
                    float nx = e1y * e2z - e1z * e2y;
                    float ny = e1z * e2x - e1x * e2z;
                    float nz = e1x * e2y - e1y * e2x;
                    float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (len > 1e-6f) {
                        nx /= len; ny /= len; nz /= len;
                    } else {
                        nx = 0f; ny = 1f; nz = 0f;
                    }
                    float[][] uvs = {{0f, 0f}, {1f, 0f}, {1f, 1f}, {0f, 1f}};
                    int[] bases = {b0, b1, b2, b3};
                    for (int c = 0; c < 4; c++) {
                        int base = bases[c];
                        rawVerts.set(base + 4, uvs[c][0]);
                        rawVerts.set(base + 5, uvs[c][1]);
                        rawVerts.set(base + 7, nx);
                        rawVerts.set(base + 8, ny);
                        rawVerts.set(base + 9, nz);
                    }
                }
                if (slot > 0 && EntityTextureAtlas.INSTANCE.hasEmission(slot)) {
                    stageCapturedQuadsEmitters(slot, startVert, endVert, 2.8f);
                }
            } else if (slot > 0 && EntityTextureAtlas.INSTANCE.hasEmission(slot) && endVert > startVert) {
                stageCapturedQuadsEmitters(slot, startVert, endVert, 1.8f);
            }
        } catch (RuntimeException e) {
            if (captureFailures.getAndIncrement() == 0) {
                LOGGER.warn("[VK-ENTITY-CAPTURE] custom geometry capture failed renderType={}", renderType, e);
            }
        } finally {
            frameConsumer.setFlags(savedFlags);
            frameConsumer.setTexture(0, null);
        }
        if (implicit) {
            endEntity();
        }
    }

    /**
     * Captures the targeted block selection wireframe outline submitted via {@code SubmitNodeCollector.submitShapeOutline}
     * as thin 3D box ribbons around each VoxelShape edge so the block selection highlight is crisp and visible in RT mode.
     */
    public synchronized void captureShapeOutline(PoseStack poseStack, net.minecraft.world.phys.shapes.VoxelShape shape,
                                                 int color, float width) {
        if (!open || poseStack == null || shape == null || shape.isEmpty()) {
            return;
        }
        boolean implicit = !insideEntity;
        if (implicit) {
            beginEntity(-1, "shape_outline", poseStack.last().pose(), EntityGeometryFrame.CapturedEntity.FLAG_BLOCK_ENTITY);
        }
        int savedFlags = frameConsumer.getFlags();
        frameConsumer.setFlags(savedFlags | 0x08);
        try {
            PoseStack.Pose pose = poseStack.last();
            frameConsumer.setTexture(0, null);
            // Ensure high visibility for the block outline even when vanilla passes 40% alpha black
            int a = (color >>> 24) & 0xFF;
            int r = (color >>> 16) & 0xFF;
            int g = (color >>> 8) & 0xFF;
            int b = color & 0xFF;
            int solidColor = (Math.max(a, 230) << 24) | (r << 16) | (g << 8) | b;
            float halfThick = Math.max(0.0022f, Math.min(0.006f, width * 0.001f));
            Vector3f dir = new Vector3f();
            Vector3f s1 = new Vector3f();
            Vector3f s2 = new Vector3f();
            shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
                float fx1 = (float) x1, fy1 = (float) y1, fz1 = (float) z1;
                float fx2 = (float) x2, fy2 = (float) y2, fz2 = (float) z2;
                dir.set(fx2 - fx1, fy2 - fy1, fz2 - fz1);
                if (dir.lengthSquared() < 1e-8f) {
                    return;
                }
                dir.normalize();
                if (Math.abs(dir.y) < 0.9f) {
                    s1.set(-dir.z, 0f, dir.x).normalize();
                } else {
                    s1.set(1f, 0f, 0f);
                }
                s2.set(dir).cross(s1).normalize();
                s1.mul(halfThick);
                s2.mul(halfThick);
                emitOutlineRibbonQuad(pose,
                    fx1 + s1.x - s2.x, fy1 + s1.y - s2.y, fz1 + s1.z - s2.z,
                    fx2 + s1.x - s2.x, fy2 + s1.y - s2.y, fz2 + s1.z - s2.z,
                    fx2 + s1.x + s2.x, fy2 + s1.y + s2.y, fz2 + s1.z + s2.z,
                    fx1 + s1.x + s2.x, fy1 + s1.y + s2.y, fz1 + s1.z + s2.z,
                    solidColor, s1.x, s1.y, s1.z);
                emitOutlineRibbonQuad(pose,
                    fx1 - s1.x + s2.x, fy1 - s1.y + s2.y, fz1 - s1.z + s2.z,
                    fx2 - s1.x + s2.x, fy2 - s1.y + s2.y, fz2 - s1.z + s2.z,
                    fx2 - s1.x - s2.x, fy2 - s1.y - s2.y, fz2 - s1.z - s2.z,
                    fx1 - s1.x - s2.x, fy1 - s1.y - s2.y, fz1 - s1.z - s2.z,
                    solidColor, -s1.x, -s1.y, -s1.z);
                emitOutlineRibbonQuad(pose,
                    fx1 + s1.x + s2.x, fy1 + s1.y + s2.y, fz1 + s1.z + s2.z,
                    fx2 + s1.x + s2.x, fy2 + s1.y + s2.y, fz2 + s1.z + s2.z,
                    fx2 - s1.x + s2.x, fy2 - s1.y + s2.y, fz2 - s1.z + s2.z,
                    fx1 - s1.x + s2.x, fy1 - s1.y + s2.y, fz1 - s1.z + s2.z,
                    solidColor, s2.x, s2.y, s2.z);
                emitOutlineRibbonQuad(pose,
                    fx1 - s1.x - s2.x, fy1 - s1.y - s2.y, fz1 - s1.z - s2.z,
                    fx2 - s1.x - s2.x, fy2 - s1.y - s2.y, fz2 - s1.z - s2.z,
                    fx2 + s1.x - s2.x, fy2 + s1.y - s2.y, fz2 + s1.z - s2.z,
                    fx1 + s1.x - s2.x, fy1 + s1.y - s2.y, fz1 + s1.z - s2.z,
                    solidColor, -s2.x, -s2.y, -s2.z);
            });
        } catch (RuntimeException ignored) {
        } finally {
            frameConsumer.setFlags(savedFlags);
            frameConsumer.setTexture(0, null);
        }
        if (implicit) {
            endEntity();
        }
    }

    private void emitOutlineRibbonQuad(PoseStack.Pose pose,
                                       float x0, float y0, float z0,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float x3, float y3, float z3,
                                       int argb, float nx, float ny, float nz) {
        float[][] pts = {{x0, y0, z0, 0f, 0f}, {x1, y1, z1, 1f, 0f}, {x2, y2, z2, 1f, 1f}, {x3, y3, z3, 0f, 1f}};
        for (float[] pt : pts) {
            frameConsumer.addVertex(pose, pt[0], pt[1], pt[2]);
            frameConsumer.setColor(argb);
            frameConsumer.setUv(pt[3], pt[4]);
            frameConsumer.setUv2(240, 240);
            frameConsumer.setNormal(pose, nx, ny, nz);
        }
    }

    public synchronized void endEntity() {
        if (!insideEntity) {
            return;
        }

        frameConsumer.flushRemaining();
        int endVertex = frameConsumer.getVertexCount();
        int endIndex = frameConsumer.getIndexCount();
        int entityVertices = endVertex - entityStartVertex;
        int entityIndices = endIndex - entityStartIndex;

        if (entityVertices > 0 && entityIndices > 0) {
            entitiesCaptured.incrementAndGet();
            totalVertices.addAndGet(entityVertices);
            totalIndices.addAndGet(entityIndices);

            // Compute local/world bounding box from captured vertices
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

            List<Float> allVerts = frameConsumer.getVertices();
            for (int i = entityStartVertex * 10; i < endVertex * 10 && i + 2 < allVerts.size(); i += 10) {
                float vx = allVerts.get(i);
                float vy = allVerts.get(i + 1);
                float vz = allVerts.get(i + 2);
                if (vx < minX) minX = vx;
                if (vy < minY) minY = vy;
                if (vz < minZ) minZ = vz;
                if (vx > maxX) maxX = vx;
                if (vy > maxY) maxY = vy;
                if (vz > maxZ) maxZ = vz;
            }

            EntityGeometryFrame.CapturedEntity entity = new EntityGeometryFrame.CapturedEntity(
                curEntityId,
                curEntityType,
                curTransform,
                curTransform,
                entityStartVertex,
                entityVertices,
                entityStartIndex,
                entityIndices,
                1,
                curFlags,
                false
            );
            stagedEntities.add(entity);

            EntitySceneRecord sceneRec = new EntitySceneRecord(
                curEntityId,
                entityStartVertex,
                entityVertices,
                0,
                1,
                curTransform,
                curTransform,
                curFlags,
                geometryGeneration
            );
            stagedSceneRecords.add(sceneRec);

            if (loggedFirstCapture.compareAndSet(false, true)) {
                LOGGER.info("[VK-ENTITY-CAPTURE] first entity captured: type={} vertices={} indices={} tris={}",
                    curEntityType, entityVertices, entityIndices, entityIndices / 3);
            }
        }

        insideEntity = false;
        frameConsumer.setFlags(0);
        frameConsumer.setAllowHurt(true);
    }

    public synchronized EntityGeometryFrame endFrame() {
        open = false;
        insideEntity = false;
        frameConsumer.flushRemaining();
        geometryGeneration++;
        publishedEmitters = java.util.Arrays.copyOf(worldEmitters, worldEmitterCount * RtEmitters.FLOATS);
        publishedEmitterCount = worldEmitterCount;
        publishedParticleEmitterCount = particleEmitterCount;

        List<Float> rawVerts = frameConsumer.getVertices();
        List<Integer> rawIndices = frameConsumer.getIndices();
        io.github.andrexarlay.vulkancompat.api.rt.RtSceneApi.emitActiveInstances(rawVerts, rawIndices, stagedEntities);

        int totalVerts = rawVerts.size() / 10;
        int totalIndices = rawIndices.size();

        float[] vertexData = new float[rawVerts.size()];
        for (int i = 0; i < rawVerts.size(); i++) {
            vertexData[i] = rawVerts.get(i);
        }

        int[] indexData = new int[rawIndices.size()];
        for (int i = 0; i < rawIndices.size(); i++) {
            indexData[i] = rawIndices.get(i);
        }

        EntityGeometryFrame frame = new EntityGeometryFrame(
            currentFrameId,
            new ArrayList<>(stagedEntities),
            totalVerts,
            totalIndices,
            geometryGeneration,
            vertexData,
            indexData,
            null,
            null,
            null
        );

        // Track per-frame vertex/index count for diagnostics
        this.lastFrameVertices = totalVerts;
        this.lastFrameIndices = totalIndices;

        // Transition to PUBLISHED if we have geometry, EMPTY if not
        if (this.lastFrameVertices > 0) {
            this.state = State.PUBLISHED;
        } else if (this.state == State.CAPTURING) {
            this.state = State.EMPTY;
        }
        // If state was DISABLED, keep DISABLED (no geometry expected)

        // Submit to RtEntityGeometryManager
        RtEntityGeometryManager.getInstance().setCurrentFrame(frame);

        return frame;
    }

    /**
     * Called by the GPU upload / RT world pass to mark that this frame's geometry has been consumed.
     * Transitions PUBLISHED -> CONSUMED. Safe to call even if state is EMPTY or DISABLED.
     */
    public synchronized void markConsumed(long renderFrameId) {
        this.renderFrameId = renderFrameId;
        this.latencyFrames = Math.max(0L, renderFrameId - captureFrameId);
        if (this.state == State.PUBLISHED) {
            this.state = State.CONSUMED;
        }
    }

    /** Returns the current lifecycle state. */
    public State getState() { return state; }

    public synchronized String formatDiagnostics() {
        return String.format(
            "[VK-ENTITY-CAPTURE] state=%s captureFrame=%d renderFrame=%d latencyFrames=%d" +
            " entitiesSeen=%d entitiesCaptured=%d vertices=%d indices=%d triangles=%d" +
            " lastEntityType=%s lastError=%s",
            state,
            captureFrameId,
            renderFrameId,
            latencyFrames,
            entitiesSeen.get(),
            entitiesCaptured.get(),
            lastFrameVertices,
            lastFrameIndices,
            lastFrameIndices / 3,
            lastEntityType,
            lastError
        );
    }

    public long getCaptureFailures() { return captureFailures.get(); }
    public long getHookInvocations() { return hookInvocations.get(); }
    public long getEntitiesSeen() { return entitiesSeen.get(); }
    public long getEntitiesCaptured() { return entitiesCaptured.get(); }
    public long getTotalVertices() { return totalVertices.get(); }
    public long getTotalIndices() { return totalIndices.get(); }
}
