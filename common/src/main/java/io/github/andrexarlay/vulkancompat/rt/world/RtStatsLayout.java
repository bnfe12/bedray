package io.github.andrexarlay.vulkancompat.rt.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Byte layout of one per-frame slot of StatsBuf (buffer StatsBuf in rt_common.glsl): written by
 * RtFrameRenderer / RtSkyState / RtFogState, read by the shaders through pc.statsAddr.
 * <p>
 * The member list below is the single Java source of truth: offsets are computed here with the same
 * std430 rules the GLSL compiler applies to the declaration, and {@code RtStatsLayoutTest} parses the
 * GLSL struct and asserts both lists are identical (names, order, types, offsets, total size) — a
 * one-sided edit fails the build instead of silently misreading the frame slot.
 */
public final class RtStatsLayout {
    /** One member of buffer StatsBuf: GLSL declaration and its computed std430 placement. */
    public record Member(String name, String glslType, int offset, int size) {}

    private static final List<Member> MEMBERS = new ArrayList<>();
    private static int cursor;

    private static int add(String name, String glslType) {
        int align;
        int size;
        switch (glslType) {
            case "float", "uint", "int" -> { align = 4; size = 4; }
            case "vec2", "uvec2", "ivec2" -> { align = 8; size = 8; }
            case "vec3", "uvec3", "ivec3" -> { align = 16; size = 12; }
            case "vec4", "uvec4", "ivec4" -> { align = 16; size = 16; }
            case "mat4" -> { align = 16; size = 64; }
            default -> throw new IllegalStateException("unsupported StatsBuf member type " + glslType);
        }
        cursor = (cursor + align - 1) & -align;
        int offset = cursor;
        MEMBERS.add(new Member(name, glslType, offset, size));
        cursor += size;
        return offset;
    }

    /** std430 offsets of a member list given as GLSL type names (RtFrameParams push-constant block). */
    public static int[] std430Offsets(String... types) {
        int[] out = new int[types.length];
        int c = 0;
        for (int i = 0; i < types.length; i++) {
            int align;
            int size;
            switch (types[i]) {
                case "float", "uint", "int" -> { align = 4; size = 4; }
                case "vec2", "uvec2", "ivec2" -> { align = 8; size = 8; }
                case "vec3", "uvec3", "ivec3" -> { align = 16; size = 12; }
                case "vec4", "uvec4", "ivec4" -> { align = 16; size = 16; }
                case "mat4" -> { align = 16; size = 64; }
                default -> throw new IllegalStateException("unsupported member type " + types[i]);
            }
            c = (c + align - 1) & -align;
            out[i] = c;
            c += size;
        }
        return out;
    }

    public static final int HITS = add("hits", "uint");
    public static final int SAMPLES = add("samples", "uint");
    public static final int LUM_LOG_SUM = add("lumLogSum", "uint");
    public static final int HIST_ACCEPTED = add("histAccepted", "uint");
    public static final int CAM_REL = add("camRel", "vec4");
    public static final int EMITTER_ADDR = add("emitterAddr", "uvec2");
    public static final int EMITTER_COUNT = add("emitterCount", "uint");
    public static final int EXPOSURE = add("exposure", "float");
    public static final int GI_DIST = add("giDist", "float");
    public static final int EMIS_DIST = add("emisDist", "float");
    public static final int AO_RADIUS = add("aoRadius", "float");
    public static final int AUTO_NORMAL_STRENGTH = add("autoNormalStrength", "float");
    public static final int SUN_RADIUS = add("sunRadius", "float");
    public static final int HISTORY_CAP = add("historyCap", "float");
    public static final int PREV_VIEW_PROJ = add("prevViewProj", "mat4");
    public static final int CAM_DELTA = add("camDelta", "vec4");
    public static final int FOG_DENSITY = add("fogDensity", "float");
    public static final int FOG_INSCATTERING = add("fogInscattering", "float");
    public static final int FOG_DISTANCE = add("fogDistance", "float");
    public static final int FOG_MODE = add("fogMode", "uint");
    public static final int FOG_DISTANCE_SCALE = add("fogDistanceScale", "float");
    public static final int ENTITY_TRIS = add("entityTris", "uint");
    public static final int ENT_POS_ADDR = add("entPosAddr", "uvec2");
    public static final int ENT_IDX_ADDR = add("entIdxAddr", "uvec2");
    public static final int ENT_ATTR_ADDR = add("entAttrAddr", "uvec2");
    public static final int ENT_PREV_ADDR = add("entPrevAddr", "uvec2");
    public static final int ENT_ATLAS_ADDR = add("entAtlasAddr", "uvec2");
    public static final int ENT_RECT_ADDR = add("entRectAddr", "uvec2");
    public static final int MOTION_ADDR = add("motionAddr", "uvec2");
    public static final int ENT_ATLAS_SIZE = add("entAtlasSize", "uint");
    public static final int SKY_CAM_Y = add("skyCamY", "float");
    public static final int CAMERA_JITTER = add("cameraJitter", "vec2");
    public static final int SKY_SUN_DIR = add("skySunDir", "vec4");
    public static final int SKY_MOON_DIR = add("skyMoonDir", "vec4");
    public static final int SKY_FOG_COLOR = add("skyFogColor", "vec4");
    public static final int SKY_SUNSET = add("skySunset", "vec4");
    public static final int SKY_CLOUD_COLOR = add("skyCloudColor", "vec4");
    public static final int SKY_CLOUD_POS = add("skyCloudPos", "vec4");
    public static final int SKY_MISC = add("skyMisc", "vec4");
    public static final int SKY_TEX_ADDR = add("skyTexAddr", "uvec2");
    public static final int SKY_TEX_FLAGS = add("skyTexFlags", "uint");
    public static final int SKY_TILT = add("skyTilt", "float");
    public static final int CAMERA_BOB_ORIGIN = add("cameraBobOrigin", "vec4");
    public static final int CAMERA_FORWARD = add("cameraForward", "vec4");
    public static final int FOG_VANILLA = add("fogVanilla", "vec4");
    public static final int FOG_MISC = add("fogMisc", "vec4");
    public static final int FOG_MEDIUM = add("fogMedium", "vec4");
    public static final int FOG_FADE = add("fogFade", "vec4");
    public static final int SHADOW_PIXEL = add("shadowPixel", "float");
    public static final int RESERVOIR_ADDR = add("reservoirAddr", "uvec2");
    public static final int LEAF_BACKLIT = add("leafBacklit", "uint");
    public static final int SELF_AVATAR_TRI_START = add("selfAvatarTriStart", "uint");
    public static final int SELF_AVATAR_TRI_COUNT = add("selfAvatarTriCount", "uint");

    /** std430 size of the struct (struct alignment 16). */
    public static final int SIZE = (cursor + 15) & -16;
    /** Slot spacing of the frame slot ring (RtFrameRenderer STATS_SLOTS); >= {@link #SIZE}. */
    public static final int SLOT_STRIDE = 512;

    static {
        if (SIZE > SLOT_STRIDE) {
            throw new IllegalStateException("StatsBuf struct grew to " + SIZE + " bytes, slot is " + SLOT_STRIDE);
        }
    }

    /** The declared members in GLSL declaration order, for the source-check test. */
    public static List<Member> members() {
        return List.copyOf(MEMBERS);
    }

    private RtStatsLayout() {
    }
}
