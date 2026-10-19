package io.github.andrexarlay.vulkancompat.rtx.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import io.github.andrexarlay.vulkanbridge.api.LibraryResolution;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.platform.LoaderAccess;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rt.world.RtAtmosphereSettings;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rt.world.RtWorld;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Diagnostics report for bug reports ({@code /bedray report} on NeoForge, the "Copy diagnostics report" button of the settings page
 * on both loaders). Collects technical facts, scrubs them ({@link DiagnosticsScrubber}), writes
 * {@code <gamedir>/bedray/reports/bedray-report-<time>.txt} and copies the text to the clipboard. Nothing is sent over the network and
 * no identifier of the player is included (the session token, the session id and the profile are never read; the nick and the UUID are
 * read only to be removed from the text). Render thread only (it reads the live renderer state).
 * <p>
 * Every section is collected independently: a failing collector yields one line with the exception class and is logged, the rest of
 * the report is still produced (rule: errors are not hidden, they are part of the report).
 */
public final class Diagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-DIAG");
    private static final int LOG_ENTRIES = 40;
    private static final int LOG_CONTINUATION = 6;
    private static final int LOG_TAIL_BYTES = 2 * 1024 * 1024;
    private static final int LOG_LINE = 300;
    private static final double AVG_SECONDS = 30.0;

    private Diagnostics() {}

    /** Result of {@link #generate}. {@code file} is null when writing failed ({@code error} says why). */
    public record Result(Path file, String text, String error) {
    }

    /** Builds the report, writes the file and copies the text to the clipboard (clipboard failure is reported in the result). */
    public static Result generateAndCopy() {
        Result r = generate();
        String note = r.error();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.keyboardHandler.setClipboard(r.text());
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[VKB-DIAG] clipboard copy failed", e);
            note = (note == null ? "" : note + "; ") + "clipboard: " + e.getClass().getSimpleName();
        }
        return new Result(r.file(), r.text(), note);
    }

    /** Builds the (scrubbed) report text and writes it into {@code <gamedir>/bedray/reports}. */
    public static Result generate() {
        Instant now = Instant.now();
        Path gameDir = gameDir();
        DiagnosticsScrubber scrubber = scrubber(gameDir);
        List<DiagnosticsReport.Section> sections = new ArrayList<>();
        sections.add(collect("Versions", () -> versions(), scrubber, false));
        sections.add(collect("System", () -> system(), scrubber, false));
        sections.add(collect("GPU and Vulkan", () -> gpu(), scrubber, false));
        sections.add(collect("Bedray settings (bedray.toml, no paths)", () -> settings(gameDir), scrubber, false));
        sections.add(collect("Runtime state (as shown in F3)", () -> runtimeState(), scrubber, true));
        sections.add(collect("Requested / selected / runtime", () -> statuses(), scrubber, true));
        sections.add(collect("Performance (last " + (int) AVG_SECONDS + " s and last 2 min)", () -> performance(), scrubber, false));
        sections.add(collect("Counters", () -> counters(), scrubber, false));
        sections.add(collect("Native libraries (file names only)", () -> natives(), scrubber, true));
        sections.add(collect("Last WARN / ERROR lines of latest.log", () -> logTail(gameDir), scrubber, true));
        // the last pass over the whole text: identity data only (IP addresses were handled in the free-text sections)
        String text = scrubber.scrubIdentity(DiagnosticsReport.render(now, sections));
        Path dir = gameDir.resolve("bedray").resolve("reports");
        Path file = dir.resolve(DiagnosticsReport.fileName(now));
        try {
            Files.createDirectories(dir);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            LOGGER.info("[VKB-DIAG] report written: {} ({} bytes)", file.getFileName(), text.length());
            return new Result(file, text, null);
        } catch (IOException e) {
            LOGGER.error("[VKB-DIAG] cannot write the report", e);
            return new Result(null, text, "write failed: " + e.getClass().getSimpleName());
        }
    }

    /** {@link #generateAndCopy()} plus a chat line and a toast. Used by the settings button and the command. */
    public static void generateAndNotify() {
        Result r = generateAndCopy();
        Minecraft mc = Minecraft.getInstance();
        String shown = r.file() == null ? "(file not written)" : "bedray/reports/" + r.file().getFileName();
        Component msg = Component.translatableWithFallback("bedray.diag.done",
            "[Bedray] Diagnostics report copied to the clipboard and saved to " + shown + (r.error() == null ? "" : " (" + r.error() + ")"),
            shown);
        if (mc != null && mc.gui != null) {
            try {
                mc.gui.hud.getChat().addClientSystemMessage(msg);
                SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatableWithFallback("bedray.diag.toast", "Bedray diagnostics report"),
                    Component.literal(shown));
            } catch (RuntimeException e) {
                LOGGER.error("[VKB-DIAG] cannot show the confirmation", e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ plumbing

    private interface Body {
        List<String> lines() throws Exception;
    }

    private static DiagnosticsReport.Section collect(String title, Body body, DiagnosticsScrubber scrubber, boolean freeText) {
        List<String> lines;
        try {
            lines = new ArrayList<>(body.lines());
        } catch (Exception | LinkageError e) {
            LOGGER.warn("[VKB-DIAG] section '{}' failed", title, e);
            lines = new ArrayList<>();
            lines.add("(unavailable: " + e.getClass().getSimpleName() + ")");
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            String clean = DiagnosticsReport.stripFormatting(l);
            out.add(freeText ? scrubber.scrub(clean) : scrubber.scrubIdentity(clean));
        }
        return new DiagnosticsReport.Section(title, out);
    }

    private static Path gameDir() {
        try {
            return Platforms.get().gameDir().toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Path.of("").toAbsolutePath().normalize();
        }
    }

    private static DiagnosticsScrubber scrubber(Path gameDir) {
        String nick = null;
        String uuid = null;
        try {
            var user = Minecraft.getInstance().getUser();
            nick = user.getName();
            uuid = String.valueOf(user.getProfileId());
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[VKB-DIAG] no user for the scrubber", e);
        }
        String host = firstNonBlank(System.getenv("COMPUTERNAME"), System.getenv("HOSTNAME"));
        String account = firstNonBlank(System.getProperty("user.name"), System.getenv("USERNAME"), System.getenv("USER"));
        String home = System.getProperty("user.home");
        return DiagnosticsScrubber.of(nick, uuid, host, account, home, gameDir.toString());
    }

    private static String firstNonBlank(String... v) {
        for (String s : v) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------ sections

    private static List<String> versions() {
        List<String> l = new ArrayList<>();
        String bedray = Diagnostics.class.getPackage() == null ? null : Diagnostics.class.getPackage().getImplementationVersion();
        l.add("Bedray: " + (bedray == null ? "unknown (no manifest version)" : bedray));
        LoaderAccess la = Platforms.get();
        l.add("Loader: " + la.loaderName() + " " + la.loaderVersion() + (la.isDevEnvironment() ? " (dev environment)" : ""));
        l.add("Minecraft: " + net.minecraft.SharedConstants.getCurrentVersion().name());
        l.add("Sodium: " + (la.isModLoaded("sodium") ? sodiumVersion() : "not installed"));
        l.add("Iris: " + (la.isModLoaded("iris") ? "installed" : "not installed"));
        l.add("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        List<String> jvm = new ArrayList<>();
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-Dvkrtx") || a.startsWith("-Dvkbridge") || a.startsWith("-Xmx") || a.startsWith("-Xms")
                || a.startsWith("-XX:+Use") || a.startsWith("-XX:-Use")) {
                jvm.add(a);
            }
        }
        l.add("JVM options (Bedray and memory only): " + (jvm.isEmpty() ? "none" : String.join(" ", jvm)));
        return l;
    }

    private static String sodiumVersion() {
        try {
            return net.caffeinemc.mods.sodium.client.SodiumClientMod.getVersion();
        } catch (RuntimeException | LinkageError e) {
            return "installed (version unreadable: " + e.getClass().getSimpleName() + ")";
        }
    }

    private static List<String> system() {
        List<String> l = new ArrayList<>();
        l.add("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        l.add("CPU: " + cpuModel() + ", " + Runtime.getRuntime().availableProcessors() + " logical processors");
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            l.add(String.format(Locale.ROOT, "RAM: %d MB total, %d MB free; JVM max heap %d MB", os.getTotalMemorySize() >> 20,
                os.getFreeMemorySize() >> 20, Runtime.getRuntime().maxMemory() >> 20));
        }
        return l;
    }

    private static String cpuModel() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                String out = run(5, "reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString");
                for (String line : out.split("\r?\n")) {
                    int i = line.indexOf("REG_SZ");
                    if (i >= 0) {
                        return line.substring(i + 6).trim();
                    }
                }
            } else if (Files.isReadable(Path.of("/proc/cpuinfo"))) {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    if (line.startsWith("model name")) {
                        return line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            LOGGER.debug("[VKB-DIAG] cpu model not read", e);
        }
        String id = System.getenv("PROCESSOR_IDENTIFIER");
        return id == null ? "unknown" : id;
    }

    /** Runs a fixed command (no shell) with a timeout and returns its output. */
    private static String run(int seconds, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] data = p.getInputStream().readNBytes(16384);
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static List<String> gpu() {
        List<String> l = new ArrayList<>();
        DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
        l.add("GPU: " + info.name() + " (vendor " + info.vendorName() + ")");
        l.add("Backend: " + info.backendName());
        l.add("Driver: " + info.driverInfo());
        VkPhysicalDevice phys = VulkanCapabilityChecker.getVkPhysicalDevice();
        if (phys != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                VK10.vkGetPhysicalDeviceProperties(phys, props);
                int v = props.apiVersion();
                l.add("Vulkan API: " + (v >> 22) + "." + ((v >> 12) & 0x3FF) + "." + (v & 0xFFF)
                    + ", vendor id 0x" + Integer.toHexString(props.vendorID()) + ", device id 0x" + Integer.toHexString(props.deviceID()));
                VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.malloc(stack);
                VK10.vkGetPhysicalDeviceMemoryProperties(phys, mem);
                long local = 0;
                for (int i = 0; i < mem.memoryHeapCount(); i++) {
                    VkMemoryHeap h = mem.memoryHeaps(i);
                    if ((h.flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                        local += h.size();
                    }
                }
                l.add("VRAM (device-local heaps): " + (local >> 20) + " MB");
            }
        } else {
            l.add("Vulkan physical device: not available (not a Vulkan backend?)");
        }
        l.add("Hardware ray tracing supported: " + VulkanCapabilityChecker.isHardwareRayTracingSupported());
        VkDevice dev = VulkanCapabilityChecker.getVkDevice();
        TreeSet<String> enabled = new TreeSet<>();
        if (dev != null) {
            for (Field f : dev.getCapabilities().getClass().getFields()) {
                if (f.getType() == boolean.class && !Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("VK_")) {
                    try {
                        if (f.getBoolean(dev.getCapabilities())) {
                            enabled.add(f.getName());
                        }
                    } catch (IllegalAccessException e) {
                        LOGGER.debug("[VKB-DIAG] capability {} unreadable", f.getName(), e);
                    }
                }
            }
        }
        for (String ext : VulkanCapabilityChecker.REQUIRED_RT_EXTENSIONS) {
            l.add("Extension " + ext + ": " + (enabled.contains(ext) ? "enabled" : "NOT enabled")
                + (VulkanCapabilityChecker.hasExtension(ext) ? " (supported)" : " (not supported by the device)"));
        }
        l.add("Enabled device extensions: " + enabled.size() + " -> " + String.join(", ", enabled));
        return l;
    }

    private static List<String> settings(Path gameDir) throws IOException {
        List<String> l = new ArrayList<>();
        l.add("effective rt mode: " + VulkanRtxConfig.effectiveRtMode().logName() + ", experimental rays unlocked: " + VulkanRtxConfig.raysUnlocked());
        l.add("RT presets: rt=" + RtSettings.preset + ", pt=" + RtSettings.pathPreset + ", path: " + RtSettings.pathSummary());
        l.add("atmosphere: " + RtAtmosphereSettings.describe());
        Path toml = null;
        try {
            toml = Platforms.get().configDir().resolve("bedray.toml");
        } catch (RuntimeException e) {
            toml = gameDir.resolve("config").resolve("bedray.toml");
        }
        if (Files.isRegularFile(toml)) {
            l.add("-- bedray.toml --");
            for (String line : Files.readAllLines(toml, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                String low = t.toLowerCase(Locale.ROOT);
                if (low.contains("path") && low.contains("=") && (low.contains("/") || low.contains("\\"))) {
                    continue;   // a stored file path is not part of the report
                }
                l.add(t);
            }
        } else {
            l.add("bedray.toml: not found");
        }
        return l;
    }

    private static List<String> runtimeState() {
        List<String> l = new ArrayList<>();
        for (F3Sections.Section s : F3Sections.ALL) {
            l.addAll(F3Sections.lines(s));
        }
        return l;
    }

    private static List<String> statuses() {
        List<String> l = new ArrayList<>();
        l.add("RT geometry gate: enabled=" + RtGeometryGate.enabled() + ", block reason=" + RtGeometryGate.blockReason()
            + (RtGeometryGate.failure() == null ? "" : ", failure=" + RtGeometryGate.failure()));
        l.add("RT pass: selected=" + VulkanRtxConfig.effectiveRtMode().logName() + ", last pass=" + RtFrameRenderer.lastPassMode()
            + ", world ready=" + RtFrameRenderer.worldReady());
        l.add("Upscaler: requested=" + UpscalerRequest.current() + ", active=" + TemporalUpscaler.active());
        l.add("Denoiser: requested=" + RtFrameRenderer.denoiserRequested() + ", selected=" + RtFrameRenderer.denoiserSelected()
            + ", runtime=" + RtFrameRenderer.denoiserRuntimeState() + ", resolution reason=" + RtFrameRenderer.denoiserResolutionReason()
            + (RtFrameRenderer.denoiserRuntimeFailure() == null ? "" : ", failure=" + RtFrameRenderer.denoiserRuntimeFailure()));
        io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime.Snapshot fg = io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime.snapshot();
        l.add("Frame generation: mode=" + io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings.mode()
            + ", availability=" + io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime.availability()
            + ", state=" + fg.status().state() + ", reason=" + fg.status().reason());
        return l;
    }

    private static List<String> performance() {
        List<String> l = new ArrayList<>();
        long now = System.nanoTime();
        PerfHistory.Summary s30 = PerfHistory.instance().summary(now, AVG_SECONDS);
        PerfHistory.Summary s120 = PerfHistory.instance().summary(now, 120.0);
        l.add("last " + (int) AVG_SECONDS + " s: " + s30.format());
        l.add("last " + (int) AVG_SECONDS + " s GPU ms: " + s30.formatGpu());
        l.add("last 2 min: " + s120.format());
        l.add("last 2 min GPU ms: " + s120.formatGpu());
        l.add("GPU timestamps: " + (PerfMonitor.gpuUnavailableReason() == null ? "available" : "off (" + PerfMonitor.gpuUnavailableReason() + ")"));
        return l;
    }

    private static List<String> counters() {
        List<String> l = new ArrayList<>();
        RtWorld.FrameStats s = RtFrameRenderer.lastStats();
        if (s == null) {
            l.add("RT counters: no RT frame yet");
        } else {
            l.add("TLAS instances: " + s.instances() + " (" + String.format(Locale.ROOT, "%.2f", s.tlasMs()) + " ms)");
            l.add("BLAS sections: " + s.totalSections() + ", built this frame: " + s.builtThisFrame() + ", pending: " + s.pending()
                + ", failed: " + s.failedSections() + ", total built: " + RtFrameRenderer.blasBuiltTotal());
            l.add("RT memory: " + (s.vramBytes() >> 20) + " MB");
        }
        l.add("Light list (emitters): " + RtFrameRenderer.lightTelemetry());
        return l;
    }

    private static List<String> natives() {
        List<String> l = new ArrayList<>();
        for (LibraryResolution r : VulkanBridge.libraries().scan()) {
            StringBuilder b = new StringBuilder(r.id()).append(": ").append(r.status().state());
            b.append(" [");
            boolean first = true;
            for (String f : r.descriptor().requiredFiles()) {
                String ver = r.versions().get(f);
                b.append(first ? "" : ", ").append(f).append(r.files().containsKey(f) ? " found" : " missing");
                if (ver != null) {
                    b.append(' ').append(ver);
                }
                first = false;
            }
            b.append(']');
            if (!r.usable()) {
                b.append(" - ").append(r.status().detail());
            }
            l.add(b.toString());
        }
        return l;
    }

    private static List<String> logTail(Path gameDir) {
        List<String> lines = DiagnosticsReport.warnErrorLines(gameDir.resolve("logs").resolve("latest.log"), LOG_ENTRIES, LOG_CONTINUATION,
            LOG_TAIL_BYTES, LOG_LINE);
        if (lines.isEmpty()) {
            lines.add("(no WARN / ERROR entries, or latest.log not found)");
        }
        return lines;
    }
}
