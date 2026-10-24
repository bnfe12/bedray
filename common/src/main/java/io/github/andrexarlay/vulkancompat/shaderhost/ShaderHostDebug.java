package io.github.andrexarlay.vulkancompat.shaderhost;

import io.github.andrexarlay.vulkancompat.rtx.debug.DebugInfo;
import io.github.andrexarlay.vulkancompat.shaderhost.customrt.CustomRtStage;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtRuntime;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisRenderBridge;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;

import java.util.List;

/**
 * F3 lines of the shader hosts ("diag" section): which host was requested and which renders, why a pack is refused,
 * the Iris post runtime state, Dirt RT and the custom RT stage. Registered on the first rendered frame, not at the early
 * bootstrap, because {@link DebugInfo} touches game classes.
 */
public final class ShaderHostDebug {
    private static boolean registered;

    private ShaderHostDebug() {}

    public static void ensureRegistered() {
        if (registered) return;
        registered = true;
        DebugInfo.register(DebugInfo.provider("shader_host", ShaderHostDebug::appendLines));
    }

    static void appendLines(List<String> out) {
        ShaderHostSelector.Selection selection = ShaderHosts.selection();
        if (selection == null) {
            out.add("[SHADER-HOST] not bootstrapped");
        } else {
            out.add("[SHADER-HOST] requested=" + selection.requested().configName() + " active=" + selection.active().configName()
                + " state=" + selection.requestedStatus().state());
            if (selection.requested() == ShaderHostKind.IRIS) {
                out.add("[IRIS] " + clip(selection.requestedStatus().detail()));
                IrisShaderHost iris = ShaderHosts.iris();
                PackReport report = iris == null ? null : iris.lastReport();
                if (report != null && !report.plannedWork().isEmpty()) {
                    out.add("[IRIS] missing for this pack: " + clip(String.join(" | ", report.plannedWork())));
                }
                out.add("[IRIS-POST] " + clip(IrisRenderBridge.runtimeStatus()));
            }
        }
        DirtRtRuntime.appendDebugLines(out);
        CustomRtStage.appendDebugLines(out);
    }

    private static String clip(String s) {
        if (s == null) return "";
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        return flat.length() > 220 ? flat.substring(0, 220) + "..." : flat;
    }
}
