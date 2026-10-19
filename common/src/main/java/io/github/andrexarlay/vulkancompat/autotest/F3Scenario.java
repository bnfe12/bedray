package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.rtx.debug.DebugInfo;
import io.github.andrexarlay.vulkancompat.rtx.debug.F3Entry;
import io.github.andrexarlay.vulkancompat.rtx.debug.F3Sections;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;

import java.lang.management.ManagementFactory;
import java.util.Collection;

/**
 * {@code f3}: the Bedray F3 screen in the test world. Starts with the overlay hidden and counts section rebuilds over 120 frames
 * (must be 0: F3 off costs nothing), then shows F3, logs every line (plain), measures the rebuild cost
 * ({@code [VKB-F3] build_us}) and the per-frame cost of {@code display()} with the cached lists, including allocated bytes,
 * and takes screenshots with F3 visible. Run with {@code -Dvkrtx.rt.mode=path}; add {@code -Dvkrtx.framegen=2x} for the generated fps line.
 */
public final class F3Scenario implements AutotestScenario {
    @Override
    public String name() {
        return "f3";
    }

    @Override
    public void run(AutotestContext ctx) {
        ctx.minecraft().debugEntries.setOverlayVisible(false);
        long builds0 = F3Sections.totalBuilds();
        ctx.waitFrames(120, () -> {
            ctx.log("scenario", name(), "phase", "hidden", "builds_while_hidden", String.valueOf(F3Sections.totalBuilds() - builds0));
            ctx.minecraft().debugEntries.setOverlayVisible(true);
            ScenarioTime.waitSeconds(ctx, 12.0, () -> measure(ctx));
        });
    }

    private void measure(AutotestContext ctx) {
        for (String line : DebugInfo.lines()) {
            ctx.log("scenario", name(), "f3", DebugText.plain(line));
        }
        int n = 200;
        long ns = F3Sections.benchmarkRebuild(n);
        ctx.log("scenario", name(), "event", "rebuild", "build_us_all_sections", String.valueOf(ns / n / 1000.0), "runs", String.valueOf(n));
        ctx.log("scenario", name(), "stats", F3Sections.statsLine());

        int calls = 20000;
        int[] lineCount = {0};
        DebugScreenDisplayer sink = new DebugScreenDisplayer() {
            @Override public void addPriorityLine(String line) { lineCount[0]++; }
            @Override public void addLine(String line) { lineCount[0]++; }
            @Override public void addToGroup(Identifier group, Collection<String> lines) { lineCount[0] += lines.size(); }
            @Override public void addToGroup(Identifier group, String line) { lineCount[0]++; }
        };
        F3Entry[] entries = new F3Entry[F3Sections.ALL.length];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = new F3Entry(F3Sections.ALL[i]);
        }
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long builds = F3Sections.totalBuilds();
        for (int w = 0; w < 2000; w++) { // warm-up
            for (F3Entry e : entries) e.display(sink, null, null, null);
        }
        long a0 = mx.getThreadAllocatedBytes(tid);
        long t0 = System.nanoTime();
        for (int c = 0; c < calls; c++) {
            for (F3Entry e : entries) e.display(sink, null, null, null);
        }
        long dt = System.nanoTime() - t0;
        long alloc = mx.getThreadAllocatedBytes(tid) - a0;
        ctx.log("scenario", name(), "event", "display", "display_ns_per_frame_all_sections", String.valueOf(dt / calls),
            "alloc_bytes_per_frame", String.valueOf(alloc / calls), "rebuilds_during", String.valueOf(F3Sections.totalBuilds() - builds),
            "lines_per_frame", String.valueOf(lineCount[0] / (calls + 2000)));
        ctx.shot("01_f3.png", () -> ScenarioTime.waitSeconds(ctx, 8.0, () -> ctx.shot("02_f3_later.png", ctx::finish)));
    }
}
