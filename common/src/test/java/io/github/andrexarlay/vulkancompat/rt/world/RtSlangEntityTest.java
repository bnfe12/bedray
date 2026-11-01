package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Slang path tracer used to be blind to every living thing: it masked the entity instance out of its ray queries, so
 * mobs, items and armour stands appeared as nothing at all while the legacy GLSL path traced them. The decode is a port of
 * {@code fetchEntitySurface}, and the parts an owner can see are the ones easy to get subtly wrong - which instance bits are
 * allowed into a query, what an entity hit can be used as an identity, when the first-person body is skipped, and which of
 * the atlas alpha bands is a pane rather than a hole. These guards pin those, plus the host plumbing that keeps a frame with
 * no capture bound to a dummy instead of to a stale ring slot.
 */
class RtSlangEntityTest {

    private static final String REFERENCE_BACKEND = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/ReferencePathTraceBackend.java";
    private static final String SHADERS = "src/main/resources/shaders/slang";
    private static final String ENTITY = SHADERS + "/traversal/entity_geometry.slang";
    private static final String TRAVERSAL = SHADERS + "/traversal/legacy_section_traversal.slang";
    private static final String CONTRACT_TRAVERSAL = SHADERS + "/contracts/scene_tracer.slang";
    private static final String TRACER = SHADERS + "/pathtrace/path_tracer.slang";
    private static final String DISPLAY = SHADERS + "/passes/path_display.slang";
    private static final String SURFACE = SHADERS + "/surface/surface.slang";
    private static final String PASS = SHADERS + "/passes/path_trace.slang";
    private static final String ENTRY = SHADERS + "/pathtrace/path_trace_entry.slang";
    private static final String HOST_PASS =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtPathTraceSlangPass.java";
    private static final String HOST_FRAME =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSlangPathPass.java";
    private static final String GEOMETRY_MANAGER =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtEntityGeometryManager.java";
    private static final String CONFIG =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtConfig.java";

    /** A path tracer that cannot see a mob cannot reflect one, so the scene mask has to carry both bits. */
    @Test
    void theTracedSceneIncludesTheEntityRing() {
        String pass = read(PASS);
        assertTrue(pass.contains("static const uint PATH_TRACE_INSTANCE_MASK_SCENE = 0x03u;"),
            "terrain and the entity ring are one TLAS; the mask is what separates them from world-only passes");
        assertTrue(pass.contains("traversal.instanceMask = PATH_TRACE_INSTANCE_MASK_SCENE;"),
            "and the path-trace entry is the pass that asks for both");
        String traversal = read(TRAVERSAL);
        String trace = block(traversal, "SurfaceHit traceNearest(Ray ray, bool excludeSelfAvatar) {");
        assertTrue(trace.contains("query.TraceRayInline(tlas, RAY_FLAG_NONE, instanceMask, desc);"),
            "the mask the pass sets is the mask the query uses");
    }

    /** One instance holds both kinds of geometry, so the only honest discriminator is the instance's own bit. */
    @Test
    void anEntityHitIsRoutedByTheInstanceBitNotByAGeometryIndex() {
        String entity = read(ENTITY);
        assertTrue(entity.contains("static const uint ENTITY_INSTANCE_TYPE_BIT = 0x800000u;"),
            "the custom-index bit RtWorld writes on the entity instance");
        assertTrue(entity.contains("return (instanceId & ENTITY_INSTANCE_TYPE_BIT) != 0u;"),
            "a hit names itself; the low bits are the record index, not a section slot");

        String traversal = read(TRAVERSAL);
        String decode = block(traversal, "SurfaceHit decodeHit(Ray ray, uint instanceId, uint geometryIndex, uint primitiveId,");
        assertTrue(decode.contains("if (entityInstance(instanceId)) {"), "the entity ring is decoded before the section table");
        assertTrue(decode.contains("return entityHitToSurface(entities, cameraAnchor, ray, instanceId, primitiveId, bary, t);"),
            "through the entity decoder, which never indexes a section");
        String sectionDecode = block(traversal,
            "SurfaceHit legacyHitToSurface(StructuredBuffer<LegacySection> sections, Ray ray,");
        assertTrue(sectionDecode.contains("if (entityInstance(instanceId)) return result;"),
            "and the section decoder refuses an entity id, so a routing bug reads a freed table entry instead of writing one");
    }

    /**
     * An entity part is captured around the camera, not anchored to a chunk, so its world position is the hit plus the
     * origin the ring was written around - and it must not be used as an identity, because it repeats every 1024 blocks.
     */
    @Test
    void anEntitySurfaceHasNoSectionIdentity() {
        String entity = block(read(ENTITY),
            "SurfaceHit entityHitToSurface(EntityGeometry entities, float3 cameraAnchor, Ray ray,");
        assertTrue(entity.contains("result.worldPosition = result.position + cameraAnchor;"),
            "a camera-relative section position becomes a world position by adding the same camera delta");
        assertTrue(entity.contains("result.sectionId = SECTION_INDEX_NONE;"),
            "no section to key history or emitter identity on");

        String pass = read(PASS);
        assertTrue(pass.contains("traversal.cameraAnchor = pc.emitterCameraRelative.xyz;"),
            "the same vector the block-emitter NEE corrects with");
    }

    /**
     * The first-person body sits around the eye. It has to stay in the scene - water and glass reflect it - so it is
     * excluded per-ray at commit time, and only for the ray that leaves the eye.
     */
    @Test
    void theSelfAvatarIsSkippedByPrimaryRaysOnly() {
        String traversal = read(TRAVERSAL);
        assertTrue(traversal.contains("override SurfaceHit tracePrimary(Ray ray) {\n        return traceNearest(ray, true);"),
            "the primary ray looks past the body");
        assertTrue(traversal.contains("SurfaceHit traceClosest(Ray ray) {\n        return traceNearest(ray, false);"),
            "a bounce ray does not: the avatar is geometry the light crosses");
        String visibility = block(traversal, "VisibilityResult traceVisibility(Ray ray) {");
        assertTrue(!visibility.contains("selfAvatarPrimitive"),
            "and neither does a shadow ray - an emitters-only skip would light the body through itself");
        String skip = block(traversal, "bool selfAvatarPrimitive(uint instanceId, uint primitive) {");
        assertTrue(skip.contains("if (selfAvatarTriCount == 0u || !entityInstance(instanceId)) return false;"),
            "a count of 0 (third person, or the option off) skips nothing at all");

        String pass = read(PASS);
        assertTrue(pass.contains("traversal.selfAvatarTriStart = pc.entityState.y;"), "the range comes from the push");
        assertTrue(pass.contains("traversal.selfAvatarTriCount = pc.entityState.z;"));
    }

    /**
     * The atlas alpha byte does double duty, so the three bands are a contract with {@code EntityTextureAtlas}, and the
     * fullbright floor is applied after them - which is what the legacy chain does.
     */
    @Test
    void theAlphaBandsAndTheFullbrightFloorOfTheEntityDecode() {
        String entity = read(ENTITY);
        assertTrue(entity.contains("static const float ENTITY_ALPHA_HOLE = 0.1;"), "vanilla's entity cutout edge");
        assertTrue(entity.contains("static const float ENTITY_ALPHA_EMISSION_CODE = 224.0 / 255.0;"),
            "at and above this the alpha byte is an emission code, not a coverage");
        assertTrue(entity.contains("static const float ENTITY_FULLBRIGHT_EMISSION = 0.4;"),
            "the floor a fullbright part is raised to");

        String surface = block(read(SURFACE), "} else if (hit.kind == HitKind.Entity) {");
        assertTrue(surface.contains("if (hit.light.x > 0.995 && hit.light.y > 0.995 && alpha >= ENTITY_ALPHA_HOLE) {"),
            "the three conditions of the vanilla cutout and fullbright rule");
        assertTrue(surface.contains("material.emissionMask = max(material.emissionMask, ENTITY_FULLBRIGHT_EMISSION);"),
            "a max, not an assignment: a glowing texel keeps the brighter of the two");
        assertTrue(surface.indexOf("ENTITY_FULLBRIGHT_EMISSION") > surface.indexOf("material.alphaMode = AlphaMode.Cutout;"),
            "and it runs after the band is resolved, so a hole stays unlit");
        assertTrue(surface.contains("glassy = entityTexelTranslucent(texel) && max(color.r, max(color.g, color.b)) > 0.08;"),
            "a black texel is the shadow blob under the model, not a pane");
        assertTrue(surface.contains("color = lerp(color, float3(1.0, 0.05, 0.05), 0.65);"),
            "the hurt tint, at 65%");

    }

    /** The count the shader reads is the length it was bound, so a stale ring slot can never be walked. */
    @Test
    void theRingSliceIsBoundAtItsOwnOffset() {
        String manager = read(GEOMETRY_MANAGER);
        assertTrue(manager.contains("new GpuRange(ringPos, ringSlot * ringVertexCap * 12L, (long) frameVertices * 12L)"),
            "positions: the slot's own start, for this frame's vertices only");
        assertTrue(manager.contains("new GpuRange(ringIdx, ringSlot * ringIndexCap * 4L, (long) frameTriangles * 12L)"),
            "indices: three uint per triangle, so the bound length is the count");
        assertTrue(manager.contains("new GpuRange(ringAttr, ringSlot * ringVertexCap * 16L, (long) frameVertices * 16L)"),
            "attributes: one uint4 per vertex");

        String entity = read(ENTITY);
        String count = block(entity, "uint entityTriangleCount(EntityGeometry entities) {");
        assertTrue(count.contains("entities.indices.GetDimensions(count, stride);"),
            "the bound length, not a push constant, is the authority");
        assertTrue(count.contains("return count / ENTITY_INDICES_PER_TRIANGLE;"));

        String host = read(HOST_PASS);
        assertTrue(host.contains("private static SlangProgram.Resource entityBuffer(String name, BufferView view, BedrayGpuBuffersNative.Buffer dummy) {"),
            "every entity binding falls back to the pass's own dummy");
        assertTrue(host.contains("entityScalarDummy = zeroedBuffer(ENTITY_SCALAR_BYTES);")
                && host.contains("entityVectorDummy = zeroedBuffer(ENTITY_VECTOR_BYTES);")
                && host.contains("nativeBuffers.write(buffer, 0, stack.calloc(bytes));"),
            "a zeroed element of the right size, so a frame that captured nothing reads zeros");
        assertTrue(host.contains("throw new IllegalArgumentException(\"the push names an entity atlas but no texel range is bound\")"),
            "and a push that names a count with no buffer behind it is rejected before the dispatch");
    }

    /** The frame pulls the capture of the frame it is recording; the switch family keeps an A/B check available. */
    @Test
    void theFrameHandsTheCapturedRingToThePass() {
        String frame = read(HOST_FRAME) + "\n" + read(REFERENCE_BACKEND);
        assertTrue(frame.contains("RtPathTraceSlangPass.Entities capturedEntities = entityBindingsOfThisFrame();"),
            "one place reads the manager, so the push and the descriptors cannot disagree");
        assertTrue(frame.contains("trace.record(cmd, scene, atlases, emitters, reservoirRange, ptRange, capturedEntities,\n"
            + "            RtPathTraceSlangPass.Guides.of(views.gbuffer()), motion, views.depth(), input.stats(), frame, dispatch)"),
            "the ring travels with the rest of the frame's resources");
        assertTrue(frame.contains("if (!RtConfig.SLANG_ENTITIES)"), "and the switch turns the whole thing off");

        String config = read(CONFIG);
        assertTrue(config.contains("System.getProperty(\"vkbridge.rt.slangEntities\", \"true\")"),
            "on by default: a path tracer blind to mobs is the bug, not the fallback");
    }

    /**
     * "The mobs are not in the traced picture" has two very different causes - a capture that never filled the ring, and a
     * trace that ignores a ring that is full - and only the counts say which. The report is one line per session, recorded
     * before the dispatch, so a pass that then fails on a bad range still leaves the evidence behind.
     */
    @Test
    void theFrameReportsWhatTheRingActuallyHolds() {
        String frame = read(HOST_FRAME) + "\n" + read(REFERENCE_BACKEND);
        assertTrue(frame.contains("if (!entityRingReported) {"), "one line per session, not one per frame");
        assertTrue(frame.contains("Slang path trace reads the entity ring"), "the populated case names its counts");
        assertTrue(frame.contains("Slang path trace has no entity capture in this frame"),
            "and the empty case is reported too, because silence is what made this ambiguous before");
        assertTrue(frame.indexOf("Slang path trace reads the entity ring") < frame.indexOf("backend.record("),
            "the evidence is recorded before the dispatch that may fail");
        assertTrue(frame.contains("capturedEntities.indices().bytes() / 12L"),
            "triangles from the index range the shader itself measures");
        assertTrue(frame.contains("capturedEntities.positions().bytes() / 12L"), "and vertices from the position range");
    }

    /**
     * A mob that walked three blocks is not the surface that was there last frame, so its pixels have to be looked up
     * where that part's own geometry stood. The host already uploads the previous position of every captured vertex;
     * these guards pin that the shader reads it as a bounded ring, subtracts in the right direction, and stays quiet
     * when the frame has no previous slice at all.
     */
    @Test
    void aMovingPartIsFoundWhereItStoodLastFrame() {
        String entity = read(ENTITY);
        assertTrue(entity.contains("StructuredBuffer<uint> previous;"),
            "the previous-position ring is a bound buffer, not a device address");
        String previousCount = block(entity, "uint entityPreviousVertexCount(EntityGeometry entities) {");
        assertTrue(previousCount.contains("entities.previous.GetDimensions(count, stride);"),
            "its bound length is the authority, like the triangle count");
        assertTrue(previousCount.contains("return count / ENTITY_FLOATS_PER_VERTEX;"),
            "three floats per vertex, so a one-element dummy answers zero vertices");
        String motion = block(entity, "float3 entityMotion(EntityGeometry entities, uint primitive, float2 bary) {");
        assertTrue(motion.contains("if (vi.x >= vertices || vi.y >= vertices || vi.z >= vertices) {"),
            "a triangle the previous ring does not cover has no motion, rather than a read past the binding");
        assertTrue(motion.contains("return previous - current;"),
            "previous minus current: the vector a consumer ADDS to this frame's position to land last frame");
        assertTrue(motion.contains("float3 w = float3(1.0 - bary.x - bary.y, bary.x, bary.y);"),
            "the same weights the hit itself is decoded with, or the two halves are different points");

        String manager = read(GEOMETRY_MANAGER);
        String previousRange = block(manager, "public synchronized GpuRange framePreviousPositions() {");
        assertTrue(previousRange.contains("new GpuRange(ringPrev, ringSlot * ringVertexCap * 12L,"
            + " (long) frameVertices * 12L)"),
            "the same slot and the same length as the positions: a motion vector that outlives its model is a lie");

        String host = read(HOST_PASS);
        assertTrue(host.contains("entityBuffer(\"entityPrevious\", captured.previous(), entityScalarDummy)"),
            "the previous slice reaches the shader through its own range - binding it as the positions would silently"
                + " make every motion vector zero");

        String pass = read(PASS);
        assertTrue(pass.contains("entities.previous = entityPrevious;"),
            "and the pass hands that buffer to the geometry struct, or the field stays unbound and every"
                + " motion answer is the zero default");
    }

    /** Only the ray that left the eye needs a history to reproject into; the rest of the path is a sample, not a pixel. */
    @Test
    void onlyTheEyeRayIsAskedWhereItsSurfaceStood() {
        String tracer = read(TRACER);
        String primary = block(tracer, "if (!firstSurfaceAccepted) {");
        assertTrue(primary.contains("result.primaryMotion = float4(traversal.surfaceMotion(hit),"),
            "recorded once, at the surface the accumulator will look for next frame");
        assertTrue(primary.contains("hit.kind == HitKind.Entity ? 1.0 : 0.0);"),
            "and the lane that says so is the hit's own kind, not a guess from the depth");
        String empty = block(tracer, "PathTraceResult emptyPathTraceResult() {");
        assertTrue(empty.contains("result.primaryMotion = float4(0.0);"),
            "a path that never committed a surface carries no motion either");

        String contract = read(CONTRACT_TRAVERSAL);
        assertTrue(contract.contains("float3 surfaceMotion(SurfaceHit hit) {\n        return float3(0.0);"),
            "a backend that tracks no moving geometry answers zero, which is what a static reprojection already assumes");
    }

    /** Terrain does not move within a frame; asking the ring about a block face would invent a displacement. */
    @Test
    void terrainIsNeverAskedForMotionAndAMissWritesNothing() {
        String traversal = read(TRAVERSAL);
        String motion = block(traversal, "override float3 surfaceMotion(SurfaceHit hit) {");
        assertTrue(motion.contains("if (!hit.hit || hit.kind != HitKind.Entity) return float3(0.0);"),
            "the section half of the same instance is static by construction");
        assertTrue(motion.contains("return entityMotion(entities, hit.primitiveId, hit.barycentrics);"),
            "and the entity half answers from the ring through the hit's own corner data");

        String entry = read(ENTRY);
        assertTrue(entry.contains("outMotion[p] = sampleTrusted && (result.flags & PATH_TRACE_FLAG_PRIMARY_HIT) != 0u"
            + "\n        ? result.primaryMotion : float4(0.0);"),
            "a pixel that hit nothing or whose sample the tracer refused says nothing, so a moved camera restarts it"
                + " instead of borrowing the sky's history");
    }

    /** The accumulator is where a motion vector becomes a pixel: it has to be added, not merely carried. */
    @Test
    void theAccumulatorAddsTheSurfacesOwnMotionToTheReprojection() {
        String display = read(DISPLAY);
        String reproject = block(display, "PathDisplayHistory pathDisplayReproject(FrameConstants f, int2 p,"
            + " float2 size, float hitDistance,");
        assertTrue(reproject.contains("float3 previous = position + cameraDelta + surfaceMotion;"),
            "camera motion and surface motion are two different terms, and a mob needs both");
        assertTrue(display.contains("float3 surfaceMotion = motion[p].rgb;")
                && display.contains("pc.reuse.xyz, surfaceMotion, pc.reuse.w,"),
            "read per pixel from the image the tracer wrote this frame");
        assertTrue(display.contains("[[vk::binding(6, 0)]] RWTexture2D<float4> motion;"),
            "bound as the image the producer owns, not a copy that could disagree");

        String host = read(HOST_PASS);
        assertTrue(host.contains("nextMotion = nativeImages.create(w, h, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,"),
            "the producer allocates it beside the radiance, at the same size");
        assertTrue(host.contains("SlangProgram.Resource.storageImage(\"outMotion\", motion.view())"),
            "and hands its own image to the shader, so no consumer can bind a stale one");

        String frame = read(HOST_FRAME) + "\n" + read(REFERENCE_BACKEND);
        assertTrue(frame.contains("SlangProgram.Resource.storageImage(\"motion\", trace.motionView())"),
            "the display step reads the tracer's image rather than a second allocation");
        assertTrue(frame.contains("entityView(eg.framePreviousPositions())"),
            "and the previous ring reaches the pass from the same one place as the other slices");
    }

    @Test
    void endPortalPartsUseTheProceduralStarfieldInsteadOfTheirCapturedTexel() {
        String entity = read(ENTITY);
        assertTrue(entity.contains("static const uint ENTITY_VERTEX_FLAG_END_PORTAL = 0x80u;"),
            "CapturedVertexConsumer marks end_portal and end_gateway parts with bit 7");
        assertTrue(entity.contains("result.flags |= HIT_FLAG_ENTITY_END_PORTAL;"),
            "the geometry decoder carries the part classification to the material layer");

        String surface = block(read(SURFACE), "} else if (hit.kind == HitKind.Entity) {");
        assertTrue(surface.contains("entityPortalColor(hit.worldPosition, rayDirection, options.portalTime)"),
            "the portal is procedural and uses the current bounce direction for parallax");
        assertTrue(surface.contains("emission = 0.95;") && surface.contains("pbr.roughness = 0.05;"),
            "the portal emission and the smooth surface response");
        assertTrue(read(SURFACE).contains("SurfaceHit hit, MaterialSamplingOptions options, float3 rayDirection)"),
            "a secondary hit needs its own ray direction, not the camera's primary direction");
        String entry = read(ENTRY);
        assertTrue(entry.contains("materialOptions.portalTime = f.camera.origin.w;"),
            "the animation clock comes from the immutable frame block");
    }

    @Test
    void entityAutoNormalsPerturbOnlyTheShadingNormal() {
        String surface = read(SURFACE);
        assertTrue(surface.contains("float entityAtlasLuminanceOffset(EntityAtlas atlas, uint slot, float2 uv, int2 offset)"),
            "the Sobel taps are integer atlas texels, not block-atlas samples");
        assertTrue(surface.contains("float3 decodeEntityAutoNormal(EntityAtlas atlas, SurfaceHit hit,"),
            "entity normal generation has its own material-stage decoder");
        String entity = block(surface, "} else if (hit.kind == HitKind.Entity) {");
        assertTrue(entity.contains("decodeEntityAutoNormal(atlas.entities, hit, geometricNormal, options.autoNormalStrength)"),
            "the player's strength reaches the entity decode");
        assertTrue(entity.indexOf("!endPortal") < entity.indexOf("decodeEntityAutoNormal"),
            "the procedural portal must not inherit a normal from its placeholder texture");
        assertTrue(!entity.contains("hit.geometricNormal ="),
            "auto normals never change the geometric normal used to offset shadow rays");
        assertTrue(read(ENTRY).contains("materialOptions.autoNormalStrength = asfloat(pc.entityState.w);"),
            "the existing push block carries the strength without growing the ABI");
    }

    private static String block(String source, String header) {
        int at = source.indexOf(header);
        assertTrue(at >= 0, "missing block: " + header);
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block: " + header);
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
