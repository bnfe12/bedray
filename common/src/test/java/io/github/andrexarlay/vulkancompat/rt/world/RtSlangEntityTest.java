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

    private static final String SHADERS = "src/main/resources/shaders/slang";
    private static final String ENTITY = SHADERS + "/traversal/entity_geometry.slang";
    private static final String TRAVERSAL = SHADERS + "/traversal/legacy_section_traversal.slang";
    private static final String SURFACE = SHADERS + "/surface/surface.slang";
    private static final String PASS = SHADERS + "/passes/path_trace.slang";
    private static final String HOST_PASS =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtPathTraceSlangPass.java";
    private static final String HOST_FRAME =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSlangPathPass.java";
    private static final String GEOMETRY_MANAGER =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtEntityGeometryManager.java";
    private static final String CONFIG =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtConfig.java";
    private static final String LEGACY_SURFACE = "src/main/resources/shaders/glsl/rt_common_surface.glsl";

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
        String legacy = block(read(LEGACY_SURFACE), "void fetchEntitySurface(");
        assertTrue(legacy.contains("entityTriangle(prim)"),
            "the legacy path decodes the same primitive index: this is a port, not a re-derivation");
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
            "the legacy path adds camRel the same way (rt_common_surface.glsl: s.worldPos = s.pos + camRel.xyz)");
        assertTrue(entity.contains("result.sectionId = SECTION_INDEX_NONE;"),
            "no section to key history or emitter identity on");

        String pass = read(PASS);
        assertTrue(pass.contains("traversal.cameraAnchor = pc.emitterCameraRelative.xyz;"),
            "the same vector the block-emitter NEE corrects with, which is the legacy camRel");
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
    void theAlphaBandsAndTheFullbrightFloorMatchTheLegacyDecode() {
        String entity = read(ENTITY);
        assertTrue(entity.contains("static const float ENTITY_ALPHA_HOLE = 0.1;"), "vanilla's entity cutout edge");
        assertTrue(entity.contains("static const float ENTITY_ALPHA_EMISSION_CODE = 224.0 / 255.0;"),
            "at and above this the alpha byte is an emission code, not a coverage");
        assertTrue(entity.contains("static const float ENTITY_FULLBRIGHT_EMISSION = 0.4;"),
            "the floor a fullbright part is raised to");

        String surface = block(read(SURFACE), "} else if (hit.kind == HitKind.Entity) {");
        assertTrue(surface.contains("if (hit.light.x > 0.995 && hit.light.y > 0.995 && alpha >= ENTITY_ALPHA_HOLE) {"),
            "the legacy rule's own three conditions");
        assertTrue(surface.contains("material.emissionMask = max(material.emissionMask, ENTITY_FULLBRIGHT_EMISSION);"),
            "a max, not an assignment: a glowing texel keeps the brighter of the two");
        assertTrue(surface.indexOf("ENTITY_FULLBRIGHT_EMISSION") > surface.indexOf("material.alphaMode = AlphaMode.Cutout;"),
            "and it runs after the band is resolved, so a hole stays unlit");
        assertTrue(surface.contains("glassy = entityTexelTranslucent(texel) && max(color.r, max(color.g, color.b)) > 0.08;"),
            "a black texel is the shadow blob under the model, not a pane");
        assertTrue(surface.contains("color = lerp(color, float3(1.0, 0.05, 0.05), 0.65);"),
            "the hurt tint, at the legacy 65%");

        String legacy = block(read(LEGACY_SURFACE), "void fetchEntitySurface(");
        assertTrue(legacy.contains("texelEmission = max(texelEmission, 0.4);"),
            "the legacy floor this port keeps");
        assertTrue(legacy.contains("mix(col, vec3(1.0, 0.05, 0.05), 0.65)"), "and the legacy hurt tint");
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
        assertTrue(host.contains("private static SlangProgram.Resource entityBuffer(String name, BufferView view, RtMemory.Buf dummy) {"),
            "every one of the five bindings falls back to the pass's own dummy");
        assertTrue(host.contains("\"rt_path_entity_scalar_dummy\"") && host.contains("\"rt_path_entity_vector_dummy\""),
            "a zeroed element of the right size, so a frame that captured nothing reads zeros");
        assertTrue(host.contains("throw new IllegalArgumentException(\"the push names an entity atlas but no texel range is bound\")"),
            "and a push that names a count with no buffer behind it is rejected before the dispatch");
    }

    /** The frame pulls the capture of the frame it is recording; the switch family keeps an A/B check available. */
    @Test
    void theFrameHandsTheCapturedRingToThePass() {
        String frame = read(HOST_FRAME);
        assertTrue(frame.contains("RtPathTraceSlangPass.Entities capturedEntities = entityBindingsOfThisFrame();"),
            "one place reads the manager, so the push and the descriptors cannot disagree");
        assertTrue(frame.contains("trace.record(cmd, scene, atlases, emitters, reservoirRange, capturedEntities, "
            + "views.depth(), frame, dispatch)") || frame.contains("capturedEntities, views.depth(), frame, dispatch"),
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
        String frame = read(HOST_FRAME);
        assertTrue(frame.contains("if (!entityRingReported) {"), "one line per session, not one per frame");
        assertTrue(frame.contains("Slang path trace reads the entity ring"), "the populated case names its counts");
        assertTrue(frame.contains("Slang path trace has no entity capture in this frame"),
            "and the empty case is reported too, because silence is what made this ambiguous before");
        assertTrue(frame.indexOf("Slang path trace reads the entity ring") < frame.indexOf("trace.record(cmd,"),
            "the evidence is recorded before the dispatch that may fail");
        assertTrue(frame.contains("capturedEntities.indices().bytes() / 12L"),
            "triangles from the index range the shader itself measures");
        assertTrue(frame.contains("capturedEntities.positions().bytes() / 12L"), "and vertices from the position range");
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
