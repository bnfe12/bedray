package io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the GLSL sources of the built-in spatial upscalers from the host files in {@code /shaders/spatial/} and the
 * unmodified third-party headers in {@code /shaders/third_party/} (no {@code #include}: Shaderc runs without an include
 * callback here). Every text patch is checked: a pattern that is not found exactly the expected number of times throws, so a
 * changed upstream header fails loudly instead of compiling something else.
 * <p>
 * Sources (rule 13):
 * <ul>
 * <li>{@code third_party/fsr1/ffx_fsr1.h}: AMD FidelityFX SDK v2.3.0 (github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK, tag
 *     v2.3.0, commit 60f4ea81), {@code Kits/FidelityFX/upscalers/fsr3/include/gpu/fsr1/ffx_fsr1.h}, MIT (header of the file and
 *     the per-file list of {@code Kits/FidelityFX/docs/license.md}). Not modified.</li>
 * <li>{@code third_party/nis/NIS_Scaler.h}, {@code NIS_Config.h}: NVIDIA Image Scaling SDK v1.0.3 as shipped in NVIDIA Streamline
 *     SDK v2.14.1 {@code source/plugins/sl.nis/NIS/}, MIT (file headers). Not modified on disk; patched in memory here.</li>
 * </ul>
 * No Minecraft classes (unit-tested, compiled with Shaderc in {@code runShimTests}).
 */
public final class SpatialShaderSource {
    static final String FSR1_HEADER = "/shaders/third_party/fsr1/ffx_fsr1.h";
    static final String NIS_SCALER_HEADER = "/shaders/third_party/nis/NIS_Scaler.h";
    static final String NIS_CONFIG_HEADER = "/shaders/third_party/nis/NIS_Config.h";
    static final String SHIM = "/shaders/spatial/ffx_glsl_shim.glsl";

    /** kPhaseCount x kFilterSize of NIS_Config.h. */
    public static final int NIS_PHASES = 64;
    public static final int NIS_FILTER = 8;

    private SpatialShaderSource() {}

    public static String easuSource() {
        return fsr1Host("/shaders/spatial/fsr1_easu.comp");
    }

    public static String rcasSource() {
        return fsr1Host("/shaders/spatial/fsr1_rcas.comp");
    }

    private static String fsr1Host(String host) {
        String src = load(host);
        src = replaceOnce(src, "//@@SHIM@@", load(SHIM), host);
        return replaceOnce(src, "//@@FFX_FSR1@@", load(FSR1_HEADER), host);
    }

    /** NIS NVScaler host + coefficient constants + patched NIS_Scaler.h. */
    public static String nisSource() {
        String host = "/shaders/spatial/nis_scaler.comp";
        String config = load(NIS_CONFIG_HEADER);
        float[] scale = parseCoefficients(config, "coef_scale");
        float[] usm = parseCoefficients(config, "coef_usm");
        String coef = coefficientArray("vkbCoefScale", scale) + coefficientArray("vkbCoefUsm", usm)
            + "vec4 vkbCoefScaler(ivec2 p) { return vkbCoefScale[p.y * 2 + p.x]; }\n"
            + "vec4 vkbCoefUSM(ivec2 p) { return vkbCoefUsm[p.y * 2 + p.x]; }\n";
        String scaler = patchNisScaler(load(NIS_SCALER_HEADER));
        String src = load(host);
        src = replaceOnce(src, "//@@NIS_COEFFICIENTS@@", coef, host);
        return replaceOnce(src, "//@@NIS_SCALER@@", scaler, host);
    }

    /** The three in-memory patches of NIS_Scaler.h (see the class comment of nis_scaler.comp). */
    static String patchNisScaler(String s) {
        s = replaceExactly(s, "NVTEX_LOAD(coef_scaler, NVI2(vIdx, phase))", "vkbCoefScaler(NVI2(vIdx, phase))", 1, "NIS_Scaler.h");
        s = replaceExactly(s, "NVTEX_LOAD(coef_usm, NVI2(vIdx, phase))", "vkbCoefUSM(NVI2(vIdx, phase))", 1, "NIS_Scaler.h");
        s = replaceExactly(s, "#define NVTEX_STORE(x, pos, v) imageStore(x, NVI2(pos), v)",
            "#define NVTEX_STORE(x, pos, v) if (all(lessThan(NVI2(pos), imageSize(x)))) imageStore(x, NVI2(pos), v)", 1, "NIS_Scaler.h");
        return s;
    }

    /**
     * Reads {@code constexpr float <name>[kPhaseCount][kFilterSize] = { ... };} of NIS_Config.h: 64 x 8 floats, row major.
     */
    public static float[] parseCoefficients(String config, String name) {
        String start = "constexpr float " + name + "[kPhaseCount][kFilterSize] = {";
        int a = config.indexOf(start);
        if (a < 0) {
            throw new IllegalStateException("NIS_Config.h: '" + start + "' not found");
        }
        int b = config.indexOf("};", a);
        if (b < 0) {
            throw new IllegalStateException("NIS_Config.h: end of " + name + " not found");
        }
        String body = config.substring(a + start.length(), b);
        Matcher m = Pattern.compile("-?\\d+\\.\\d*f?").matcher(body);
        List<Float> values = new ArrayList<>();
        while (m.find()) {
            String t = m.group();
            values.add(Float.parseFloat(t.endsWith("f") ? t.substring(0, t.length() - 1) : t));
        }
        if (values.size() != NIS_PHASES * NIS_FILTER) {
            throw new IllegalStateException("NIS_Config.h: " + name + " has " + values.size() + " values, expected " + NIS_PHASES * NIS_FILTER);
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** GLSL {@code const vec4 name[128]}: element (phase * 2 + half) = row {@code phase}, floats {@code half*4 .. half*4+3}. */
    static String coefficientArray(String name, float[] v) {
        StringBuilder sb = new StringBuilder("const vec4 ").append(name).append('[').append(v.length / 4).append("] = vec4[](\n");
        for (int i = 0; i < v.length; i += 4) {
            sb.append(String.format(Locale.ROOT, "    vec4(%.6f, %.6f, %.6f, %.6f)", v[i], v[i + 1], v[i + 2], v[i + 3]));
            sb.append(i + 4 < v.length ? ",\n" : "\n");
        }
        return sb.append(");\n").toString();
    }

    static String replaceOnce(String s, String what, String with, String where) {
        return replaceExactly(s, what, with, 1, where);
    }

    static String replaceExactly(String s, String what, String with, int expected, String where) {
        int count = 0;
        int i = 0;
        while ((i = s.indexOf(what, i)) >= 0) {
            count++;
            i += what.length();
        }
        if (count != expected) {
            throw new IllegalStateException(where + ": expected " + expected + " occurrence(s) of '" + what + "', found " + count
                + " (upstream header changed?)");
        }
        return s.replace(what, with);
    }

    static String load(String path) {
        try (InputStream in = SpatialShaderSource.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("shader resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read shader resource " + path, e);
        }
    }
}
