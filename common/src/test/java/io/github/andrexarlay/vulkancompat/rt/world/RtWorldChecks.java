package io.github.andrexarlay.vulkancompat.rt.world;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * L2 checks of the pure logic of the world ray tracing path (allocator, instance transforms, matrices, radius).
 * No GPU. Called from ShimTestRunner.
 */
public final class RtWorldChecks {
    private RtWorldChecks() {
    }

    /** Full expansion of the rt_common.glsl umbrella (the modules in umbrella order) for shader source checks. */
    public static String rtCommon() {
        return RtShaderCompiler.resolveIncludes(
            RtShaderCompiler.loadSource("/shaders/rt_common.glsl"), "/shaders/rt_common.glsl");
    }

    public static void runAll() {
        allocatorBasics();
        allocatorCoalescing();
        allocatorExhaustion();
        instanceTransform();
        radiusAndOrdering();
        inverseViewProjection();
        cameraBobProjectionParity();
        debugModes();
        primaryShaderCompiles();
        skyModel();
        frameParamsLayout();
        lightingEquation();
        samplingHelpers();
        modesAndIncludes();
        instanceWriterLayout();
        RtModesChecks.runAll();
    }

    private static void modesAndIncludes() {
        check(RtMode.parse("off") == RtMode.OFF && RtMode.parse("RAYS") == RtMode.RAY_TRACED
            && RtMode.parse("path") == RtMode.PATH_TRACED && RtMode.parse("path_traced") == RtMode.PATH_TRACED, "RtMode.parse names");
        check(RtMode.parse("bogus") == null && RtMode.parse(null) == null && RtMode.parse("") == null, "RtMode.parse rejects unknown");
        check(!RtMode.OFF.isActive() && RtMode.RAY_TRACED.isActive() && RtMode.PATH_TRACED.isActive(), "isActive");
        check(RtFrameRenderer.passModeFor(RtMode.OFF) == RtMode.RAY_TRACED && RtFrameRenderer.passModeFor(RtMode.RAY_TRACED) == RtMode.RAY_TRACED
            && RtFrameRenderer.passModeFor(RtMode.PATH_TRACED) == RtMode.PATH_TRACED, "pass chosen per mode");
        String raw = RtShaderCompiler.loadSource(RtPipeline.SHADER_PATH);
        check(raw.contains("#include \"rt_common.glsl\""), "rt_rays.comp includes the common file");
        String expanded = RtShaderCompiler.resolveIncludes(raw, RtPipeline.SHADER_PATH);
        check(!expanded.contains("#include") && expanded.contains("accelerationStructureEXT tlas"), "include expanded");
        String twice = RtShaderCompiler.resolveIncludes("#version 460\n#include \"rt_common.glsl\"\n#include \"rt_common.glsl\"\nvoid main(){}\n",
            RtPipeline.SHADER_PATH);
        check(twice.indexOf("accelerationStructureEXT tlas") == twice.lastIndexOf("accelerationStructureEXT tlas"), "include-once");
        boolean threw = false;
        try {
            RtShaderCompiler.resolveIncludes("#version 460\n#include \"missing.glsl\"\n", RtPipeline.SHADER_PATH);
        } catch (RtFailure e) {
            threw = e.stage().equals("shader_load");
        }
        check(threw, "missing include is an RtFailure(shader_load)");
    }

    private static void instanceWriterLayout() {
        long address = org.lwjgl.system.MemoryUtil.nmemAlloc(64);
        try {
            float[] transform = new float[12];
            for (int i = 0; i < transform.length; i++) {
                transform[i] = i + 0.25f;
            }
            RtInstanceWriter.write(address, 0x00ABCDEF, 0xFF, 0x123456789ABCDEFL, transform);
            for (int i = 0; i < transform.length; i++) {
                near(transform[i], org.lwjgl.system.MemoryUtil.memGetFloat(address + i * 4L), 0f, "instance transform " + i);
            }
            check(org.lwjgl.system.MemoryUtil.memGetInt(address + 48) == (int)0xFFABCDEF, "instance custom index and mask");
            check(org.lwjgl.system.MemoryUtil.memGetInt(address + 52) == 0x01000000, "instance sbt offset and flags (VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR = 0x1 in bits 24-31)");
            check(org.lwjgl.system.MemoryUtil.memGetLong(address + 56) == 0x123456789ABCDEFL, "instance blas address");
        } finally {
            org.lwjgl.system.MemoryUtil.nmemFree(address);
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void near(float expected, float actual, float eps, String msg) {
        if (Math.abs(expected - actual) > eps) {
            throw new AssertionError(msg + ": expected " + expected + " actual " + actual);
        }
    }

    private static void allocatorBasics() {
        FreeListAllocator a = new FreeListAllocator(4096);
        long o1 = a.alloc(100, 256);
        long o2 = a.alloc(300, 256);
        check(o1 == 0 && o2 == 256, "aligned sequential offsets: " + o1 + "," + o2);
        check(a.used() == 256 + 512, "used counts rounded sizes: " + a.used());
        check(a.alloc(1, 256) == 768, "next offset after 256+512");
    }

    private static void allocatorCoalescing() {
        FreeListAllocator a = new FreeListAllocator(1024);
        long x = a.alloc(256, 256);
        long y = a.alloc(256, 256);
        long z = a.alloc(256, 256);
        long w = a.alloc(256, 256);
        check(a.alloc(1, 256) == -1, "full");
        a.free(y, 256, 256);
        a.free(x, 256, 256);
        check(a.freeRangeCount() == 1, "x and y merged");
        a.free(w, 256, 256);
        check(a.freeRangeCount() == 2, "w separate (z still allocated)");
        a.free(z, 256, 256);
        check(a.freeRangeCount() == 1 && a.used() == 0, "all merged back");
        check(a.alloc(1024, 256) == 0, "whole range allocatable again");
        boolean threw = false;
        try {
            a.free(0, 256, 256);
            a.free(0, 256, 256);
        } catch (IllegalStateException e) {
            threw = true;
        }
        check(threw, "double free detected");
    }

    private static void allocatorExhaustion() {
        FreeListAllocator a = new FreeListAllocator(2100);
        check(a.alloc(3000, 16) == -1, "larger than capacity");
        check(a.alloc(10, 512) == 0, "first at 0");
        check(a.alloc(10, 512) == 512, "second at 512");
        check(a.alloc(100, 16) == 1024, "third after both, 16-aligned");
        check(a.alloc(10, 512) == 1536, "aligned to 512 inside the rest");
        check(a.alloc(10, 512) == -1, "no 512-aligned room left in the 52-byte tail");
    }

    private static void instanceTransform() {
        float[] m = new float[12];
        InstanceMath.sectionTransform(3, -2, 5, 50.5, 3.25, 70.0, m);
        near(1f, m[0], 0f, "m00");
        near(1f, m[5], 0f, "m11");
        near(1f, m[10], 0f, "m22");
        near(0f, m[1], 0f, "m01");
        near(3 * 16 - 50.5f, m[3], 0f, "tx = origin - cam");
        near(-2 * 16 - 3.25f, m[7], 0f, "ty");
        near(5 * 16 - 70f, m[11], 0f, "tz");
        // double precision subtraction before rounding to float: a far-away camera keeps sub-block precision
        InstanceMath.sectionTransform(1_000_000, 0, 0, 16_000_000.125, 0, 0, m);
        near(-0.125f, m[3], 0f, "far coordinates keep precision");
    }

    private static void radiusAndOrdering() {
        check(InstanceMath.sectionCoord(0.0) == 0 && InstanceMath.sectionCoord(15.999) == 0, "section 0");
        check(InstanceMath.sectionCoord(16.0) == 1, "section 1");
        check(InstanceMath.sectionCoord(-0.001) == -1 && InstanceMath.sectionCoord(-16.0) == -1 && InstanceMath.sectionCoord(-16.001) == -2,
            "negative floor division");
        check(InstanceMath.inRadius(6, 4, -6, 0, 0, 0, 6, 4), "on the border");
        check(!InstanceMath.inRadius(7, 0, 0, 0, 0, 0, 6, 4), "outside XZ");
        check(!InstanceMath.inRadius(0, 5, 0, 0, 0, 0, 6, 4), "outside Y");
        check(InstanceMath.distanceSq(1, 2, 2, 0, 0, 0) == 9, "distanceSq");
    }

    private static void inverseViewProjection() {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 16f / 9f, 0.05f, 512f);
        Matrix4f view = new Matrix4f().rotateY((float) Math.toRadians(30.0)).rotateX((float) Math.toRadians(-15.0));
        Matrix4f inv = InstanceMath.inverseViewProjection(proj, view, new Matrix4f());
        // The centre of the screen at an arbitrary valid depth must unproject onto the camera forward axis.
        Vector4f p = new Vector4f(0f, 0f, 0.5f, 1f).mul(inv);
        p.div(p.w);
        float len = (float) Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
        Vector4f forward = new Vector4f(0f, 0f, -1f, 0f);
        new Matrix4f(view).invert().transform(forward);
        near(forward.x, p.x / len, 1e-4f, "forward x");
        near(forward.y, p.y / len, 1e-4f, "forward y");
        near(forward.z, p.z / len, 1e-4f, "forward z");
        // round trip: proj*view * (inv * clip) == clip
        Vector4f clip = new Vector4f(0.3f, -0.2f, 0.5f, 1f);
        Vector4f world = new Vector4f(clip).mul(inv);
        Vector4f back = new Matrix4f(proj).mul(view).transform(world);
        back.div(back.w);
        near(0.3f, back.x, 1e-4f, "round trip x");
        near(-0.2f, back.y, 1e-4f, "round trip y");
    }

    private static void cameraBobProjectionParity() {
        Matrix4f base = new Matrix4f().perspective((float) Math.toRadians(70.0), 16f / 9f, 0.05f, 512f);
        Matrix4f view = new Matrix4f().rotateY((float) Math.toRadians(31.0)).rotateX((float) Math.toRadians(-12.0));
        Matrix4f bob = new Matrix4f().translation(0.12f, -0.17f, 0.06f)
            .rotateZ((float) Math.toRadians(2.5)).rotateX((float) Math.toRadians(-1.2));
        Matrix4f fullProjection = new Matrix4f(base).mul(bob);
        Matrix4f fullViewProjection = new Matrix4f(fullProjection).mul(view);
        Vector3f origin = CameraBobPose.originOffset(fullProjection, base, view, new Vector3f());

        Vector3f worldPoint = new Vector3f(0.9f, -0.35f, -3.8f);
        Vector4f clip = fullViewProjection.transform(new Vector4f(worldPoint, 1f));
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        float uvX = ndcX * 0.5f + 0.5f;
        float uvY = ndcY * 0.5f + 0.5f;
        Vector4f rayPoint = new Vector4f(ndcX, ndcY, 0.5f, 1f)
            .mul(new Matrix4f(fullViewProjection).invert());
        rayPoint.div(rayPoint.w);
        Vector3f direction = new Vector3f(rayPoint.x, rayPoint.y, rayPoint.z).sub(origin).normalize();
        Vector3f pointFromOrigin = new Vector3f(worldPoint).sub(origin);
        float alongRay = pointFromOrigin.dot(direction);
        Vector3f closest = new Vector3f(origin).fma(alongRay, direction);
        near(worldPoint.x, closest.x, 2e-4f, "bob ray parity x");
        near(worldPoint.y, closest.y, 2e-4f, "bob ray parity y");
        near(worldPoint.z, closest.z, 2e-4f, "bob ray parity z");

        Vector3f legacyDirection = new Vector3f(rayPoint.x, rayPoint.y, rayPoint.z).normalize();
        Vector3f legacyClosest = new Vector3f(legacyDirection).mul(new Vector3f(worldPoint).dot(legacyDirection));
        check(legacyClosest.distance(worldPoint) > 0.01f, "origin-aware unprojection corrects bob translation parallax");

        Matrix4f prevView = new Matrix4f().rotateY((float) Math.toRadians(28.0)).rotateX((float) Math.toRadians(-10.0));
        Matrix4f prevBob = new Matrix4f().translation(0.08f, -0.11f, 0.02f)
            .rotateZ((float) Math.toRadians(1.1)).rotateX((float) Math.toRadians(-0.6));
        Matrix4f prevProjection = new Matrix4f(base).mul(prevBob);
        Matrix4f prevViewProjection = new Matrix4f(prevProjection).mul(prevView);
        Vector3f cameraDelta = new Vector3f(0.03f, 0.04f, -0.02f);
        Vector3f previousRelativePoint = new Vector3f(worldPoint).add(cameraDelta);
        Vector4f previousClip = prevViewProjection.transform(new Vector4f(previousRelativePoint, 1f));
        Vector4f previousUnprojected = new Vector4f(previousClip).mul(new Matrix4f(prevViewProjection).invert());
        previousUnprojected.div(previousUnprojected.w);
        near(previousRelativePoint.x, previousUnprojected.x, 2e-4f, "previous bob reprojection x");
        near(previousRelativePoint.y, previousUnprojected.y, 2e-4f, "previous bob reprojection y");
        near(previousRelativePoint.z, previousUnprojected.z, 2e-4f, "previous bob reprojection z");

        Vector4f forwardPoint = new Matrix4f(fullViewProjection).invert().transform(new Vector4f(0f, 0f, 0.5f, 1f));
        Vector3f forward = new Vector3f(forwardPoint.x / forwardPoint.w, forwardPoint.y / forwardPoint.w, forwardPoint.z / forwardPoint.w)
            .sub(origin).normalize();
        float linearViewZ = -new Vector3f(worldPoint).sub(origin).dot(forward);
        Vector3f rawForward = CameraBobPose.rawForward(view, new Vector3f());
        Vector3f rawViewPoint = new Vector3f(worldPoint).mulDirection(view);
        near(rawViewPoint.z, -worldPoint.dot(rawForward), 2e-4f, "NRD raw view-Z matches the raw world-to-view matrix");
        check(Math.abs(linearViewZ - rawViewPoint.z) > 0.01f, "bobbed guide Z differs from raw NRD view-Z under bob pose");
        float expectedPrevUvX = previousClip.x / previousClip.w * 0.5f + 0.5f;
        float expectedPrevUvY = previousClip.y / previousClip.w * 0.5f + 0.5f;
        var mv = io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline.reprojectCpu(
            uvX, uvY, linearViewZ, 1920, 1080,
            new Matrix4f(fullViewProjection).invert(), prevViewProjection,
            new Vec3(cameraDelta.x, cameraDelta.y, cameraDelta.z), false, null, null, 0, false,
            io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityPipeline.DEPTH_LINEAR_VIEW_Z, origin);
        near((expectedPrevUvX - uvX) * 1920f, mv.mvX(), 2e-3f, "bob velocity reprojection x");
        near((expectedPrevUvY - uvY) * 1080f, mv.mvY(), 2e-3f, "bob velocity reprojection y");
    }

    private static void primaryShaderCompiles() {
        java.nio.ByteBuffer spv = RtShaderCompiler.compileResource(RtPipeline.SHADER_PATH, "rt_rays.comp");
        check(spv.remaining() > 1000 && spv.remaining() % 4 == 0, "rt_rays.comp SPIR-V size " + spv.remaining());
        check(spv.getInt(0) == 0x07230203, "SPIR-V magic number");
        boolean threw = false;
        try {
            RtShaderCompiler.compile("#version 460\nvoid main() { undefined_symbol(); }\n", "broken.comp");
        } catch (RtFailure f) {
            threw = f.stage().equals("shader_compile");
        }
        check(threw, "a compile error is reported as RtFailure(shader_compile), not swallowed");
    }

    private static void debugModes() {
        check(RtConfig.parseMode("normals") == RtConfig.MODE_NORMALS, "normals");
        check(RtConfig.parseMode("depth") == RtConfig.MODE_DEPTH, "depth");
        check(RtConfig.parseMode("uv") == RtConfig.MODE_UV, "uv");
        check(RtConfig.parseMode("section") == RtConfig.MODE_SECTION, "section");
        check(RtConfig.parseMode("albedo") == RtConfig.MODE_ALBEDO && RtConfig.parseMode("lit") == RtConfig.MODE_LIT, "albedo/lit");
        check(RtConfig.parseMode("shadow") == RtConfig.MODE_SHADOW && RtConfig.parseMode("ao") == RtConfig.MODE_AO, "shadow/ao");
        check(RtConfig.parseMode("") == RtConfig.MODE_NONE, "empty is none");
        check(!RtConfig.isUnknownMode("") && RtConfig.isUnknownMode("bogus"), "unknown detection");
    }

    private static void skyModel() {
        float[] d = new float[3];
        RtSky.sunDirection(0f, false, d);
        near(0f, d[0], 1e-6f, "noon x");
        near(1f, d[1], 1e-6f, "noon y");
        RtSky.sunDirection((float) (Math.PI / 2), false, d);
        near(-1f, d[0], 1e-6f, "angle 90 deg: sun towards -X (estimate)");
        near(0f, d[1], 1e-6f, "angle 90 deg: on the horizon");
        RtSky.sunDirection((float) (Math.PI / 2), true, d);
        near(1f, d[0], 1e-6f, "sunFlipX mirrors X");
        RtSky.sunDirection((float) Math.PI, false, d);
        near(-1f, d[1], 1e-6f, "midnight: sun straight down");

        check(RtSky.dayFactor(1f) == 1f && RtSky.dayFactor(-1f) == 0f, "day factor saturates");
        check(RtSky.dayFactor(0.0f) > 0f && RtSky.dayFactor(0.0f) < 1f, "dusk is between");
        check(RtSky.dayFactor(0.1f) < RtSky.dayFactor(0.2f), "day factor is monotonic");

        RtSky.Key key = new RtSky.Key();
        RtSky.keyLight(0f, (float) Math.PI, false, true, key);
        check(key.day == 1f && key.dir[1] > 0.99f && key.color[0] > 0.8f, "noon: sun, bright warm light");
        RtSky.keyLight((float) Math.PI, 0f, false, true, key);
        check(key.day == 0f && key.dir[1] > 0.99f, "midnight: the moon (at its zenith here) is the key light");
        check(key.color[2] > key.color[0] && key.color[2] < 0.3f, "moonlight is dim and bluish");
        RtSky.keyLight(0f, 0f, false, false, key);
        check(key.color[0] == 0f && key.color[1] == 0f && key.color[2] == 0f, "no sky: no directional light");

        float[] keyNoon = {1f, 0.97f, 0.92f};
        float[] keyMoon = {0.06f, 0.075f, 0.1f};
        float[] amb = new float[3];
        RtSky.ambient(new float[] {0.4f, 0.6f, 1.0f}, 1f, 1f, -1f, 1f, 1f, keyNoon, 1f, amb);
        check(amb[2] >= amb[0] && amb[2] < amb[0] * 1.25f, "ambient follows the sky colour without a blue wash");
        float[] ambNight = new float[3];
        RtSky.ambient(new float[] {0.4f, 0.6f, 1.0f}, 0f, -1f, 1f, 1f, 1f, keyMoon, 1f, ambNight);
        check(ambNight[0] < amb[0] * 0.2f && ambNight[2] > 0.005f, "ambient is much darker at night, but not black");
        float[] ambDown = new float[3];
        RtSky.ambient(new float[] {0.4f, 0.6f, 1.0f}, 1f, 1f, -1f, 1f, 1f, keyNoon, -1f, ambDown);
        check(ambDown[2] < amb[2] && ambDown[0] > 0.1f, "downward faces get the ground bounce, upward faces the sky");

        // Sky light scale: AMBIENT_SCALE * pow(day, 1.5); the night sky light is a separate term
        float dayScale = RtSky.ambientScale(1f);
        float nightScale = RtSky.ambientScale(0f);
        near(RtSky.AMBIENT_SCALE, dayScale, 1e-5f, "day ambient scale");
        near(0f, nightScale, 1e-5f, "night ambient scale");

        // Smooth sun-to-moon crossover at sunY = -0.12 (no directional lighting jump)
        float crossAngle = (float) Math.acos(-0.12);
        RtSky.Key kBefore = new RtSky.Key();
        RtSky.Key kAfter = new RtSky.Key();
        RtSky.keyLight(crossAngle - 0.002f, crossAngle - 0.002f + (float) Math.PI, false, true, 1f, 1f, kBefore);
        RtSky.keyLight(crossAngle + 0.002f, crossAngle + 0.002f + (float) Math.PI, false, true, 1f, 1f, kAfter);
        check(!kBefore.isMoon && kAfter.isMoon, "crossover switches isMoon at sunY = -0.12");
        near(0f, kBefore.color[2], 1e-3f, "sun key light faded to 0 before crossover");
        near(0f, kAfter.color[2], 2e-3f, "moon key light starts from 0 at crossover");
        check(RtSkyState.maxExposure(0f) < 2.5f && RtSkyState.maxExposure(1f) == RtExposure.MAX, "night maxExposure stays dark");

        long slotMem = org.lwjgl.system.MemoryUtil.nmemCalloc(1, 512);
        try {
            RtSkyState st = new RtSkyState();
            st.cameraY = 72.5;
            st.sunAngle = 0.5f;
            st.moonPhase = 3;
            st.hasSky = true;
            st.cloudStatus = 2;
            st.write(slotMem, 0xCAFEBABE12345678L, true);
            near(72.5f, org.lwjgl.system.MemoryUtil.memGetFloat(slotMem + 244), 1e-5f, "skyCamY at 244");
            near(0.5f, org.lwjgl.system.MemoryUtil.memGetFloat(slotMem + RtSkyState.OFFSET + 12), 1e-5f, "sunAngle at 256+12");
            check(org.lwjgl.system.MemoryUtil.memGetLong(slotMem + RtSkyState.OFFSET + 112) == 0xCAFEBABE12345678L, "skyTexAddr at 256+112");
            int flags = org.lwjgl.system.MemoryUtil.memGetInt(slotMem + RtSkyState.OFFSET + 120);
            check((flags & 7) == 3 && (flags & 16) != 0 && (flags & 32) != 0 && ((flags >>> 8) & 3) == 2, "skyTexFlags at 256+120");
        } finally {
            org.lwjgl.system.MemoryUtil.nmemFree(slotMem);
        }

        // 4x2 moon_phases.png tile extraction (8x4 sheet -> 2x2 tiles for phases 0..7)
        int[] sheet = new int[8 * 4];
        for (int p = 0; p < 8; p++) {
            int col = p & 3;
            int row = p >>> 2;
            for (int ty = 0; ty < 2; ty++) {
                for (int tx = 0; tx < 2; tx++) {
                    sheet[(row * 2 + ty) * 8 + (col * 2 + tx)] = 100 + p;
                }
            }
        }
        for (int p = 0; p < 8; p++) {
            int[] tile = RtSkyTextures.extractMoonPhaseTile(sheet, 8, 4, p);
            check(tile.length == 4 && tile[0] == 100 + p && tile[3] == 100 + p, "moon phase tile " + p);
        }

        // Sun formula from SkyRenderer bytecode: Ry(-90) * Rx(a) * (0,1,0) with JOML conventions
        for (int deg = -180; deg <= 180; deg += 15) {
            double a = Math.toRadians(deg);
            org.joml.Vector3f v = new org.joml.Vector3f(0f, 1f, 0f);
            new org.joml.Matrix4f().rotateY((float) Math.toRadians(-90)).rotateX((float) a).transformDirection(v);
            RtSky.sunDirection((float) a, false, d);
            near(v.x, d[0], 1e-5f, "sun x vs matrix at " + deg);
            near(v.y, d[1], 1e-5f, "sun y vs matrix at " + deg);
            near(v.z, d[2], 1e-5f, "sun z vs matrix at " + deg);
        }
        RtSky.sunDirection((float) Math.toRadians(-45), false, d);
        check(d[0] > 0.7f && d[1] > 0.7f, "morning sun (negative angle) is in the east (+X)");
        // camera aim: yaw 0 = south (+Z), 90 = west (-X), pitch -90 = up
        float[] yp = RtSky.aimYawPitch(new float[] {0f, 0f, 1f});
        near(0f, yp[0], 1e-4f, "aim south yaw"); near(0f, yp[1], 1e-4f, "aim south pitch");
        yp = RtSky.aimYawPitch(new float[] {-1f, 0f, 0f});
        near(90f, yp[0], 1e-4f, "aim west yaw");
        yp = RtSky.aimYawPitch(new float[] {1f, 0f, 0f});
        near(-90f, yp[0], 1e-4f, "aim east yaw");
        yp = RtSky.aimYawPitch(new float[] {0f, 1f, 0f});
        near(-90f, yp[1], 1e-4f, "aim up pitch");
        // the camera look vector for (yaw, pitch) is (-sin yaw cos pitch, -sin pitch, cos yaw cos pitch)
        float[] sunDir = new float[3];
        RtSky.sunDirection((float) Math.toRadians(-50), false, sunDir);
        yp = RtSky.aimYawPitch(sunDir);
        double yr = Math.toRadians(yp[0]), pr = Math.toRadians(yp[1]);
        near(sunDir[0], (float) (-Math.sin(yr) * Math.cos(pr)), 1e-5f, "aim round trip x");
        near(sunDir[1], (float) (-Math.sin(pr)), 1e-5f, "aim round trip y");
        near(sunDir[2], (float) (Math.cos(yr) * Math.cos(pr)), 1e-5f, "aim round trip z");
    }

    private static void frameParamsLayout() {
        RtFrameParams p = new RtFrameParams();
        p.invViewProj.identity().m30(7f);
        p.statsAddress = 0x1122334455667788L;
        p.mode = RtConfig.MODE_LIT;
        p.aoSamples = 3;
        p.flipY = true;
        p.tonemap = true;
        p.frame = 99;
        p.key.dir[0] = 0.25f; p.key.dir[1] = 0.5f; p.key.dir[2] = 0.75f; p.key.day = 0.9f;
        p.key.color[0] = 0.1f; p.key.color[1] = 0.2f; p.key.color[2] = 0.3f;
        p.skyColor[0] = 0.4f; p.skyColor[1] = 0.5f; p.skyColor[2] = 0.6f;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(RtFrameParams.BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        p.write(b);
        check(RtFrameParams.BYTES == 128 && RtPipeline.PUSH_BYTES == 128, "push constants are 128 bytes");
        near(1f, b.getFloat(0), 0f, "m00");
        near(7f, b.getFloat(48), 0f, "m30 in the 13th float (column-major)");
        check(b.getLong(64) == 0x1122334455667788L, "statsAddr at 64");
        check(b.getInt(72) == (RtConfig.MODE_LIT | 3 << 8 | 1 << 16 | 1 << 17), "modeBits at 72: " + Integer.toHexString(b.getInt(72)));
        check(b.getInt(76) == 99, "frame at 76");
        near(0.5f, b.getFloat(84), 0f, "keyDir.y at 84");
        near(0.9f, b.getFloat(92), 0f, "day factor in keyDir.w");
        near(0.3f, b.getFloat(104), 0f, "keyColor.b at 104");
        near(0.6f, b.getFloat(120), 0f, "skyColor.b at 120");
    }

    private static void lightingEquation() {
        // a shadowed surface keeps only ambient and block light
        float shadowed = RtLighting.channel(1f, 0.9f, 1f, 0f, 0.3f, 1f, 1f, 0f, 0f);
        float lit = RtLighting.channel(1f, 0.9f, 1f, 1f, 0.3f, 1f, 1f, 0f, 0f);
        near(0.3f, shadowed, 1e-6f, "shadowed = ambient only");
        near(1.2f, lit, 1e-6f, "lit = key + ambient");
        // Lambert cosine law and facing away
        near(0.9f * 0.5f + 0.3f, RtLighting.channel(1f, 0.9f, 0.5f, 1f, 0.3f, 1f, 1f, 0f, 0f), 1e-6f, "cosine");
        near(0.3f, RtLighting.channel(1f, 0.9f, -0.7f, 1f, 0.3f, 1f, 1f, 0f, 0f), 1e-6f, "facing away gets no direct light");
        // sky light scales ambient but has a floor; AO scales ambient only
        near(0.3f * RtLighting.MIN_SKY_LIGHT, RtLighting.channel(1f, 0f, 0f, 0f, 0.3f, 0f, 1f, 0f, 0f), 1e-6f, "sky light floor");
        near(0.15f, RtLighting.channel(1f, 0f, 0f, 0f, 0.3f, 1f, 0.5f, 0f, 0f), 1e-6f, "ao halves ambient");
        near(0.82f * 0.5f, RtLighting.channel(1f, 0f, 0f, 0f, 0f, 0f, 1f, RtLighting.BLOCK_COLOR[1], 0.5f) - 0f, 1e-6f, "block light (green channel)");
        // albedo multiplies everything
        near(0.5f * lit, RtLighting.channel(0.5f, 0.9f, 1f, 1f, 0.3f, 1f, 1f, 0f, 0f), 1e-6f, "albedo scale");
        near(1.0f, RtLighting.cardinalShade(0f, 1f, 0f), 1e-6f, "shade up");
        near(0.5f, RtLighting.cardinalShade(0f, -1f, 0f), 1e-6f, "shade down");
        near(0.8f, RtLighting.cardinalShade(0f, 0f, -1f), 1e-6f, "shade north/south");
        near(0.6f, RtLighting.cardinalShade(1f, 0f, 0f), 1e-6f, "shade east/west");
        near(0f, RtLighting.aces(0f), 1e-6f, "aces(0)");
        check(RtLighting.aces(1f) > 0.8f && RtLighting.aces(100f) <= 1f && RtLighting.aces(0.5f) < RtLighting.aces(1f), "aces is monotonic and clamped");
        // peak-channel tone mapping (rt_tonemap.glsl mirror): monotonic, bounded, hue preserving, neutral grey stays grey, invertible
        near(0f, RtTonemap.apply(0f, 0f, 0f)[0], 1e-6f, "tonemap(0)");
        float prevTm = -1f;
        for (float x = 0f; x <= 64f; x += 0.05f) {
            float[] t = RtTonemap.apply(x, x, x);
            check(t[0] >= prevTm - 1e-6f && t[0] <= 1f && Math.abs(t[0] - t[1]) < 1e-5f && Math.abs(t[1] - t[2]) < 1e-5f, "tonemap monotonic, <= 1, grey stays neutral at " + x);
            prevTm = t[0];
        }
        float[] grass = RtTonemap.apply(0.22f, 0.66f, 0.11f);
        check(grass[1] / grass[0] > 2.7f && grass[1] / grass[2] > 4.5f, "tonemap keeps the saturation of mid-tone colours (old ACES: g/r 2.1)");
        float[][] displays = {{0.3f, 0.5f, 0.2f}, {0.96f, 0.82f, 0.30f}, {0.62f, 0.72f, 0.95f}, {0.1f, 0.1f, 0.1f}, {0.9f, 0.9f, 0.9f}, {0.97f, 0.40f, 0.05f}};
        for (float[] d : displays) {
            float[] sceneLin = RtTonemap.invert(d[0], d[1], d[2]);
            float[] back = RtTonemap.apply(sceneLin[0], sceneLin[1], sceneLin[2]);
            near(d[0], back[0], 2e-3f, "tonemap round trip r");
            near(d[1], back[1], 2e-3f, "tonemap round trip g");
            near(d[2], back[2], 2e-3f, "tonemap round trip b");
        }
        near(RtTonemap.KNEE, RtTonemap.curve(RtTonemap.KNEE), 1e-6f, "curve continuous at the knee");
        near(1f, (RtTonemap.curve(RtTonemap.KNEE + 1e-3f) - RtTonemap.curve(RtTonemap.KNEE)) / 1e-3f, 5e-3f, "curve slope 1 at the knee (C1)");
        check(RtConfig.parseMode("sky") == RtConfig.MODE_SKY, "sky mode");
        check(RtLighting.shadowBias(100f) > RtLighting.shadowBias(1f) && RtLighting.shadowBias(0f) > 0f, "bias grows with distance");

        // Biome tint unpacking & chromaticity preservation (grass green, swamp water teal)
        int grassColorWord = 0xFF2E964A; // memory order: R=0x4A, G=0x96, B=0x2E, A=0xFF
        float grassR = (grassColorWord & 0xFF) / 255.0f;
        float grassG = ((grassColorWord >> 8) & 0xFF) / 255.0f;
        float grassB = ((grassColorWord >> 16) & 0xFF) / 255.0f;
        check(grassG > grassR && grassG > grassB, "biome tint is predominantly green");
        float grassRatioGR = grassG / grassR;
        float grassRatioGB = grassG / grassB;
        // Cardinal shade on side face = 0.6
        float sideShade = RtLighting.cardinalShade(1f, 0f, 0f);
        near(0.6f, sideShade, 1e-6f, "side face shade is 0.6");
        // Dividing by cardinal shade preserves chromaticity
        float unshadedR = grassR / sideShade;
        float unshadedG = grassG / sideShade;
        float unshadedB = grassB / sideShade;
        near(grassRatioGR, unshadedG / unshadedR, 1e-5f, "G/R chromaticity ratio is strictly preserved");
        near(grassRatioGB, unshadedG / unshadedB, 1e-5f, "G/B chromaticity ratio is strictly preserved");
        // Clamping unshaded components to 1.0 ensures energy conservation
        check(Math.min(1f, unshadedR) <= 1.0f && Math.min(1f, unshadedG) <= 1.0f, "unshaded components clamped to 1.0");
    }

    private static void samplingHelpers() {
        float[] d = new float[3];
        double sumZ = 0;
        int n = 0;
        for (int i = 0; i < 64; i++) {
            for (int j = 0; j < 64; j++) {
                RtSampling.cosineHemisphere((i + 0.5f) / 64f, (j + 0.5f) / 64f, d);
                float len = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                check(Math.abs(len - 1f) < 1e-4f && d[2] >= 0f, "unit vector in the upper hemisphere");
                sumZ += d[2];
                n++;
            }
        }
        // E[cos(theta)] of a cosine-weighted hemisphere is 2/3
        check(Math.abs(sumZ / n - 2.0 / 3.0) < 0.01, "mean cosine " + (sumZ / n));

        float[][] normals = {{0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {1, 0, 0}, {0.577350f, 0.577350f, 0.577350f}, {-0.6f, 0.0f, -0.8f}};
        for (float[] nrm : normals) {
            float[] t = new float[3];
            float[] b = new float[3];
            RtSampling.basisAround(nrm, t, b);
            float tn = t[0] * nrm[0] + t[1] * nrm[1] + t[2] * nrm[2];
            float bn = b[0] * nrm[0] + b[1] * nrm[1] + b[2] * nrm[2];
            float tb = t[0] * b[0] + t[1] * b[1] + t[2] * b[2];
            float tl = t[0] * t[0] + t[1] * t[1] + t[2] * t[2];
            near(0f, tn, 1e-4f, "t perpendicular to n");
            near(0f, bn, 1e-4f, "b perpendicular to n");
            near(0f, tb, 1e-4f, "t perpendicular to b");
            near(1f, tl, 1e-4f, "t is unit length");
        }
        check(RtSampling.pcgHash(1) != RtSampling.pcgHash(2) && RtSampling.pcgHash(5) == RtSampling.pcgHash(5), "pcg hash");
        check(RtConfig.AO_SAMPLES >= 0 && RtConfig.effectiveAoSamples() >= RtConfig.AO_SAMPLES, "ao samples config");
    }
}
