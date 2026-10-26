package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisConstDirectives;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Values of the typed world block ({@link IrisUniforms#WORLD}, binding {@link IrisUniforms#BINDING_WORLD}) computed the way
 * Iris 1.11.7 computes them for Minecraft 26.3 (checked against its bytecode with javap): world time from the default
 * clock, sun/moon angles from the camera's environment attributes, celestial positions as gbufferModelView rotated
 * Y(-90), Z(sunPathRotation), X(angle) applied to (0, 100, 0, 1), wetness and eyeBrightnessSmooth as Iris's exponential
 * smoothing with half-lives in tenths of a second. No Minecraft dependency: {@link IrisWorldCapture} supplies the raw
 * {@link Sample} each frame; this class is pure and unit-tested.
 */
public final class IrisWorldUniforms {

    /**
     * Raw per-frame world state.
     *
     * @param clockTime    {@code ClientLevel.getDefaultClockTime()}
     * @param fixedTime    {@code dimensionType().hasFixedTime()}
     * @param endOrNether  the current dimension is the End or the Nether (Iris skips the fixed-time rule there)
     * @param sunAngleDeg  environment attribute SUN_ANGLE in degrees at the frame's partial tick
     * @param moonAngleDeg environment attribute MOON_ANGLE in degrees
     * @param eyeInWater   Iris isEyeInWater: 0 none, 1 water, 2 lava, 3 powder snow
     * @param blockLight   block light 0..15 at the camera entity's eye
     * @param skyLight     sky light 0..15 at the camera entity's eye
     */
    public record Sample(long clockTime, boolean fixedTime, boolean endOrNether, int moonPhase, float sunAngleDeg, float moonAngleDeg,
                         float rain, float[] skyColor, float[] fogColor, float fogStart, float fogEnd, int eyeInWater,
                         int blockLight, int skyLight, float nightVision, float blindness, float darkness) {
        public Sample {
            skyColor = skyColor.clone();
            fogColor = fogColor.clone();
        }
    }

    /** Pack-level constants Iris reads from const directives (defaults as in Iris's PackDirectives). */
    public record Constants(float sunPathRotation, float wetnessHalfLife, float drynessHalfLife, float eyeBrightnessHalfLife,
                            List<String> problems) {
        public Constants {
            problems = List.copyOf(problems);
        }

        public static Constants defaults() {
            return new Constants(0f, 600f, 200f, 10f, List.of());
        }

        /** Resolves the directives of all programs; conflicting explicit values are refused like target directives. */
        public static Constants resolve(List<IrisRenderTargets.ProgramDirectives> programs) {
            Map<String, Float> values = new LinkedHashMap<>();
            Map<String, String> sources = new LinkedHashMap<>();
            List<String> problems = new ArrayList<>();
            Set<String> names = Set.of("sunPathRotation", "wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife");
            for (var program : programs) {
                for (var d : program.directives()) {
                    if (!names.contains(d.name())) continue;
                    Float v = d.type() == IrisConstDirectives.Type.FLOAT || d.type() == IrisConstDirectives.Type.INT
                        ? IrisConstDirectives.number(d.value()) : null;
                    if (v == null) { problems.add(program.program() + ": " + d.name() + " needs a numeric literal"); continue; }
                    Float previous = values.putIfAbsent(d.name(), v);
                    if (previous != null && !previous.equals(v))
                        problems.add("conflicting " + d.name() + " in " + sources.get(d.name()) + " and " + program.program());
                    sources.putIfAbsent(d.name(), program.program());
                }
            }
            return new Constants(values.getOrDefault("sunPathRotation", 0f), values.getOrDefault("wetnessHalflife", 600f),
                values.getOrDefault("drynessHalflife", 200f), values.getOrDefault("eyeBrightnessHalflife", 10f), problems);
        }
    }

    /** Iris SmoothedFloat: different half-lives for rising and falling values, first sample taken as is. */
    static final class Smoothed {
        private static final double LN_OF_2 = Math.log(2.0);
        private final float decayUp, decayDown;
        private float accumulator;
        private boolean hasInitialValue;

        Smoothed(float halfLifeUp, float halfLifeDown) {
            decayUp = decay(halfLifeUp * 0.1f);
            decayDown = decay(halfLifeDown * 0.1f);
        }

        private static float decay(float halfLifeSeconds) {
            return (float) (1.0 / (halfLifeSeconds / LN_OF_2));
        }

        float update(float value, float frameTime) {
            if (!hasInitialValue) {
                accumulator = value;
                hasInitialValue = true;
                return accumulator;
            }
            float k = value > accumulator ? decayUp : decayDown;
            float factor = 1f - (float) Math.exp(-k * frameTime);
            accumulator = (1f - factor) * accumulator + factor * value;
            return accumulator;
        }
    }

    /** Smoothing state that persists across frames; recreated whenever the host retires its post resources. */
    public static final class Smoothing {
        private final Smoothed wetness, eyeBlock, eyeSky;

        public Smoothing(Constants constants) {
            wetness = new Smoothed(constants.wetnessHalfLife(), constants.drynessHalfLife());
            eyeBlock = new Smoothed(constants.eyeBrightnessHalfLife(), constants.eyeBrightnessHalfLife());
            eyeSky = new Smoothed(constants.eyeBrightnessHalfLife(), constants.eyeBrightnessHalfLife());
        }
    }

    private IrisWorldUniforms() {}

    /** Sun angle uniform: (attribute + 90) wrapped into [0, 360], divided by 360. */
    static float wrappedAngle(float degrees) {
        float a = degrees + 90f;
        if (a < 0f) a += 360f;
        else if (a > 360f) a -= 360f;
        return a;
    }

    static Vector4f celestial(Matrix4fc modelView, float sunPathRotation, float angleDegrees) {
        Matrix4f m = new Matrix4f(modelView)
            .rotateY((float) Math.toRadians(-90.0))
            .rotateZ((float) Math.toRadians(sunPathRotation))
            .rotateX((float) Math.toRadians(angleDegrees));
        return m.transform(new Vector4f(0f, 100f, 0f, 1f));
    }

    static Vector4f up(Matrix4fc modelView) {
        return new Matrix4f(modelView).rotateY((float) Math.toRadians(-90.0)).transform(new Vector4f(0f, 100f, 0f, 0f));
    }

    /**
     * Writes the std140 block. Smoothing advances by {@code frameTime} seconds every call, as Iris advances it every frame.
     *
     * @param modelView gbufferModelView of this frame (the frame UBO's matrix)
     */
    public static void write(ByteBuffer out, Sample s, Matrix4fc modelView, Constants c, Smoothing smoothing, float frameTime) {
        out.order(ByteOrder.nativeOrder());
        for (int i = 0; i < IrisUniforms.WORLD_BYTES; i += 4) out.putInt(i, 0);
        int worldTime = (int) (s.endOrNether() ? s.clockTime() % 24000L : s.fixedTime() ? 0L : s.clockTime() % 24000L);
        out.putInt(0, worldTime);
        out.putInt(4, (int) (s.clockTime() / 24000L));
        out.putInt(8, s.moonPhase());
        float sun = wrappedAngle(s.sunAngleDeg());
        boolean day = sun < 180f;
        out.putFloat(12, sun / 360f);
        out.putFloat(16, wrappedAngle(day ? s.sunAngleDeg() : s.moonAngleDeg()) / 360f);
        float rain = Math.max(0f, Math.min(1f, s.rain()));
        out.putFloat(20, rain);
        out.putFloat(24, smoothing.wetness.update(rain, frameTime));
        out.putInt(28, s.eyeInWater());
        Vector4f sunPos = celestial(modelView, c.sunPathRotation(), s.sunAngleDeg());
        Vector4f moonPos = celestial(modelView, c.sunPathRotation(), s.moonAngleDeg());
        vec3(out, 32, sunPos);
        out.putFloat(44, s.nightVision());
        vec3(out, 48, moonPos);
        out.putFloat(60, s.blindness());
        vec3(out, 64, day ? sunPos : moonPos);
        out.putFloat(76, s.darkness());
        vec3(out, 80, up(modelView));
        out.putFloat(92, s.fogStart());
        out.putFloat(96, s.skyColor()[0]).putFloat(100, s.skyColor()[1]).putFloat(104, s.skyColor()[2]);
        out.putFloat(108, s.fogEnd());
        out.putFloat(112, s.fogColor()[0]).putFloat(116, s.fogColor()[1]).putFloat(120, s.fogColor()[2]);
        int block = s.blockLight() * 16, sky = s.skyLight() * 16;
        out.putInt(128, block).putInt(132, sky);
        out.putInt(136, (int) smoothing.eyeBlock.update(block, frameTime));
        out.putInt(140, (int) smoothing.eyeSky.update(sky, frameTime));
    }

    private static void vec3(ByteBuffer out, int offset, Vector4f v) {
        out.putFloat(offset, v.x).putFloat(offset + 4, v.y).putFloat(offset + 8, v.z);
    }

    /** Uniform names whose value depends on the frame camera (celestial positions use gbufferModelView). */
    public static final Set<String> CAMERA_DEPENDENT = Collections.unmodifiableSet(new java.util.TreeSet<>(
        List.of("sunPosition", "moonPosition", "shadowLightPosition", "upPosition")));
}
