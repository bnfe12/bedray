package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies the file name of a program source (only files directly in the shaders root or in a dimension folder are
 * programs; everything else is an include). Names follow the Iris/OptiFine program list plus the Vulkanite ray-pass layout
 * ({@code rayN.rgen}, {@code rayN_M.rchit|rahit|rmiss|rint}) that Dirt RT uses.
 */
public record ProgramName(String program, Family family, Stage stage) {

    public enum Family {
        GBUFFERS, DISTANT_HORIZONS, COLORWHEEL, SHADOW, SHADOWCOMP, BEGIN, SETUP, PREPARE, DEFERRED, COMPOSITE, FINAL, RAY
    }

    public enum Stage {
        VERTEX("vsh"), FRAGMENT("fsh"), GEOMETRY("gsh"), TESS_CONTROL("tcs"), TESS_EVAL("tes"), COMPUTE("csh"),
        RAYGEN("rgen"), CLOSEST_HIT("rchit"), ANY_HIT("rahit"), MISS("rmiss"), INTERSECTION("rint");

        public final String ext;

        Stage(String ext) {
            this.ext = ext;
        }

        static Stage byExt(String ext) {
            for (Stage s : values()) {
                if (s.ext.equals(ext)) {
                    return s;
                }
            }
            return null;
        }

        public boolean isRayTracing() {
            return ordinal() >= RAYGEN.ordinal();
        }
    }

    private static final Pattern PASS = Pattern.compile("(shadowcomp|prepare|deferred|composite|begin|setup)(\\d*)(_[a-z])?");
    private static final Pattern RAY_GEN = Pattern.compile("ray\\d+");
    private static final Pattern RAY_HIT = Pattern.compile("ray\\d+_\\d+");

    /** @return null when {@code fileName} is not a program source (unknown extension or name). */
    public static ProgramName classify(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        Stage stage = Stage.byExt(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (stage == null) {
            return null;
        }
        String base = fileName.substring(0, dot);
        Family family = family(base, stage);
        return family == null ? null : new ProgramName(base, family, stage);
    }

    private static Family family(String base, Stage stage) {
        if (stage.isRayTracing()) {
            boolean ok = stage == Stage.RAYGEN ? RAY_GEN.matcher(base).matches() : RAY_HIT.matcher(base).matches();
            return ok ? Family.RAY : null;
        }
        if (base.startsWith("gbuffers_")) {
            return Family.GBUFFERS;
        }
        if (base.startsWith("dh_")) {
            return Family.DISTANT_HORIZONS;
        }
        if (base.startsWith("clrwl_")) {
            return Family.COLORWHEEL;
        }
        if (base.equals("shadow") || base.startsWith("shadow_")) {
            return Family.SHADOW;
        }
        if (base.equals("final") || base.matches("final_[a-z]")) {
            return Family.FINAL;
        }
        Matcher m = PASS.matcher(base);
        if (m.matches()) {
            return switch (m.group(1)) {
                case "shadowcomp" -> Family.SHADOWCOMP;
                case "prepare" -> Family.PREPARE;
                case "deferred" -> Family.DEFERRED;
                case "composite" -> Family.COMPOSITE;
                case "begin" -> Family.BEGIN;
                default -> Family.SETUP;
            };
        }
        return null;
    }
}
