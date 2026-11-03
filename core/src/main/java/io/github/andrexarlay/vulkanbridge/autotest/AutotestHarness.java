package io.github.andrexarlay.vulkanbridge.autotest;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import io.github.andrexarlay.vulkanbridge.core.VulkanBackendDetector;
import io.github.andrexarlay.vulkanbridge.platform.HookPriority;
import io.github.andrexarlay.vulkanbridge.platform.LoaderAccess;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Autotest harness (test harness). Active ONLY with -Dvkbridge.autotest=&lt;scenario&gt;.
 * Scenarios: menu, world, inventory, soak, world_rotate, plus any {@link AutotestScenario} registered in {@link AutotestScenarios}. One process = one scenario.
 * <p>
 * Fixed camera pose (so that runs are comparable): after the world is loaded the player is teleported to
 * ({@link #POSE_X}, {@link #POSE_Y}, {@link #POSE_Z}) with {@link #POSE_YAW}/{@link #POSE_PITCH}; override with
 * {@code -Dvkbridge.autotest.x/y/z/yaw/pitch}. The rotation is re-asserted on the client every frame (the position is
 * set by a server {@code tp}, the player then settles on the ground).
 * <p>
 * {@code world}: three shots 02_world_a/b/c.png, 30 frames apart, camera static (flicker check).
 * {@code world_rotate}: baseline r_00.png, then yaw += rotateDeg/rotateFrames per frame ({@code -Dvkbridge.autotest.rotateDeg}
 * default 90, {@code .rotateFrames} default 90) with shots r_01.png.. every 30 frames of rotation (ghost trail / motion vector check).
 * <p>
 * Log lines: {@code [VKB-AUTOTEST] key=value ...}. Screenshots: {@code <gameDir>/autotest/<scenario>/NN_name.png}.
 * <p>
 * API sources: <local decompiled sources> (all signatures there were compiled against
 * libs/minecraft-26.3-client.jar, NeoForge 26.3.0.57-beta, FML loader 12.0.9, bus 8.0.5).
 * Note: Screenshot reads mainRenderTarget, NOT the swapchain; it does not prove correct presentation (report 3.5).
 */
public final class AutotestHarness {
    public static final String PROPERTY = "vkbridge.autotest";

    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-AUTOTEST");

    private static final String WORLD_NAME = "VKB_Autotest";
    private static final long WORLD_SEED = 1234567890123L;

    // Fixed camera pose. The player enters the world at about (-41.5, 89.0, 2.5); yaw 135 / pitch 12 looks slightly
    // down so ground, sky, small-texture blocks and foliage are expected in frame (adjust with -Dvkbridge.autotest.*).
    private static final double POSE_X = -41.5;
    private static final double POSE_Y = 89.0;
    private static final double POSE_Z = 2.5;
    private static final float POSE_YAW = 135.0f;
    private static final float POSE_PITCH = 12.0f;
    private static final int SHOT_GAP_FRAMES = 30;

    private static final long TITLE_TIMEOUT_MS = 180_000L;
    private static final long WORLD_TIMEOUT_MS = 240_000L;
    private static final long TERRAIN_TIMEOUT_FRAMES = 1800L;
    private static final long SOAK_DURATION_MS = 5L * 60_000L;
    private static final long SOAK_SHOT_INTERVAL_MS = 60_000L;
    private static final int MIN_FRAMES_AFTER_SHOT = 3;

    private enum Phase {
        WAIT_TITLE, MENU_SETTLE, CREATING_WORLD, WAIT_WORLD, WORLD_SETTLE,
        INVENTORY_SETTLE, INVENTORY_CLOSE_SETTLE, SOAK, SHOT_WAIT, WAIT_FRAMES, ROTATE, CUSTOM, FINISHED
    }

    private static AutotestHarness instance;

    private final String scenario;
    /** Scenario contributed through {@link AutotestScenarios}; null for the built-in ones. */
    private AutotestScenario custom;
    private final AutotestContext context = new Ctx();
    // Minecraft does not exist yet while the mod is constructed: both are resolved on the first client tick.
    private Minecraft mc;
    private Path outDir;

    private boolean armed;          // set by first client tick hook => gameLoadFinished
    private Phase phase = Phase.WAIT_TITLE;
    private long frames;            // post-frame hook count since armed
    private long phaseStartFrame;
    private long phaseStartMs = System.currentTimeMillis();
    private final long startMs = System.currentTimeMillis();
    private boolean terrainComplete;
    private volatile boolean finishing;
    private int shotIndex;
    private int unexpectedScreens;

    // heartbeat/watchdog (separate daemon thread: keeps logging even if the render loop stalls)
    private volatile long lastFrameMs = System.currentTimeMillis();
    private volatile long framesSeen;
    private volatile String phaseName = "WAIT_TITLE";
    private volatile int lastFps;
    private static final long HEARTBEAT_MS = 30_000L;
    private static final long STALL_MS = 120_000L;

    // screenshot state
    private volatile boolean shotDone;
    private final AtomicReference<Throwable> shotError = new AtomicReference<>();
    private Path shotPath;
    private long shotRequestFrame;
    private Runnable afterShot;

    // fixed pose / rotation
    private final double poseX = Double.parseDouble(System.getProperty("vkbridge.autotest.x", String.valueOf(POSE_X)));
    private final double poseY = Double.parseDouble(System.getProperty("vkbridge.autotest.y", String.valueOf(POSE_Y)));
    private final double poseZ = Double.parseDouble(System.getProperty("vkbridge.autotest.z", String.valueOf(POSE_Z)));
    private final float poseYaw = Float.parseFloat(System.getProperty("vkbridge.autotest.yaw", String.valueOf(POSE_YAW)));
    private final float posePitch = Float.parseFloat(System.getProperty("vkbridge.autotest.pitch", String.valueOf(POSE_PITCH)));
    private final float rotateDeg = Float.parseFloat(System.getProperty("vkbridge.autotest.rotateDeg", "90"));
    private final int rotateFrames = Integer.parseInt(System.getProperty("vkbridge.autotest.rotateFrames", "90"));
    private boolean poseLocked;       // client re-asserts yaw/pitch every frame
    private boolean rotating;
    private long rotateStartFrame;
    private int rotateShotsTaken;
    private long waitUntilFrame;
    private Runnable afterWait;

    // soak
    private long soakStartMs;
    private long lastSoakShotMs;

    private AutotestHarness(String scenario) {
        this.scenario = scenario;
    }

    public static synchronized void install(String scenario) {
        if (instance != null) {
            return;
        }
        // The scenario name is validated on the first client tick (armed): mods that contribute scenarios through
        // AutotestScenarios may be constructed after this mod.
        instance = new AutotestHarness(scenario);
        LoaderAccess loader = Platforms.get();
        loader.registerClientTickHook(instance::onClientTick);
        loader.registerPostFrameHook(HookPriority.NORMAL, instance::onRenderFramePost);
        kv("event", "installed", "scenario", scenario);
    }

    private void onClientTick() {
        // The client tick hook runs only after Minecraft.gameLoadFinished (NeoForge ClientTickEvent.Pre semantics, report 1.3).
        if (!armed) {
            mc = Minecraft.getInstance();
            outDir = mc.gameDirectory.toPath().resolve("autotest").resolve(scenario);
            armed = true;
            startHeartbeat();
            phaseStartMs = System.currentTimeMillis();
            kv("event", "armed", "scenario", scenario);
            if (!AutotestScenarios.isKnown(scenario)) {
                fail("unknown_scenario " + scenario + " known=" + AutotestScenarios.knownNames(), null);
                return;
            }
            custom = AutotestScenarios.find(scenario);
        }
    }

    private void onRenderFramePost() {
        if (!armed || finishing) {
            return;
        }
        frames++;
        framesSeen = frames;
        lastFrameMs = System.currentTimeMillis();
        lastFps = mc.getFps();
        // Keep forcing it: options may be reloaded/overwritten; focus loss must never open the pause menu.
        mc.options.pauseOnLostFocus = false;
        phaseName = phase.name();
        try {
            applyPose();
            step();
        } catch (Throwable t) {
            fail("exception in harness: " + t, t);
        }
    }

    // ------------------------------------------------------------------ state machine

    private void step() throws Exception {
        long elapsedMs = System.currentTimeMillis() - phaseStartMs;
        long phaseFrames = frames - phaseStartFrame;
        switch (phase) {
            case WAIT_TITLE -> {
                Screen screen = mc.gui.screen();
                if (screen instanceof TitleScreen && mc.gui.overlay() == null) {
                    logEnvironment();
                    mc.options.pauseOnLostFocus = false;
                    if (scenario.equals("menu")) {
                        enter(Phase.MENU_SETTLE);
                    } else if (custom != null && !custom.needsWorld()) {
                        startCustom();
                    } else {
                        enter(Phase.CREATING_WORLD);
                        beginWorldCreation(screen);
                    }
                } else if (elapsedMs > TITLE_TIMEOUT_MS) {
                    fail("timeout waiting for TitleScreen; screen="
                            + (screen == null ? "null" : screen.getClass().getName()), null);
                } else if (frames % 300 == 0) {
                    kv("event", "waiting_title", "screen", screen == null ? "null" : screen.getClass().getName(),
                            "overlay", String.valueOf(mc.gui.overlay() != null));
                }
            }
            case MENU_SETTLE -> {
                if (phaseFrames >= 90) {
                    requestShot("01_menu.png", this::finish);
                }
            }
            case CREATING_WORLD -> {
                // createFreshLevel blocks and renders nested frames; nothing to do here.
            }
            case WAIT_WORLD -> {
                if (mc.level != null && mc.player != null && mc.gui.screen() == null) {
                    runWorldCommands();
                    poseLocked = true;
                    enter(Phase.WORLD_SETTLE);
                } else if (elapsedMs > WORLD_TIMEOUT_MS) {
                    fail("timeout waiting for world; level=" + (mc.level != null) + " player=" + (mc.player != null)
                            + " screen=" + (mc.gui.screen() == null ? "null" : mc.gui.screen().getClass().getName()), null);
                }
            }
            case WORLD_SETTLE -> {
                terrainComplete = mc.levelRenderer.hasRenderedAllSections();
                boolean grounded = mc.player != null && mc.player.onGround();
                boolean ready = phaseFrames >= 120
                        && ((terrainComplete && grounded) || phaseFrames >= TERRAIN_TIMEOUT_FRAMES);
                if (ready) {
                    kv(concat(new String[] {"event", "world_ready", "terrainComplete", String.valueOf(terrainComplete),
                            "grounded", String.valueOf(grounded), "settleFrames", String.valueOf(phaseFrames)}, poseKv()));
                    switch (scenario) {
                        case "world" -> requestShot("02_world_a.png", () ->
                                waitFrames(SHOT_GAP_FRAMES, () -> requestShot("02_world_b.png", () ->
                                        waitFrames(SHOT_GAP_FRAMES, () -> requestShot("02_world_c.png", this::finish)))));
                        case "world_rotate" -> requestShot("r_00.png", () -> {
                            rotating = true;
                            rotateStartFrame = frames;
                            rotateShotsTaken = 0;
                            kv(concat(new String[] {"event", "rotate_start", "deg", String.valueOf(rotateDeg),
                                    "frames", String.valueOf(rotateFrames)}, poseKv()));
                            enter(Phase.ROTATE);
                        });
                        case "inventory" -> requestShot("02_world.png", () -> {
                            mc.gui.setScreen(new InventoryScreen(mc.player));
                            enter(Phase.INVENTORY_SETTLE);
                        });
                        case "soak" -> requestShot("02_world.png", () -> {
                            soakStartMs = System.currentTimeMillis();
                            lastSoakShotMs = soakStartMs;
                            enter(Phase.SOAK);
                        });
                        default -> {
                            if (custom != null) {
                                startCustom();
                            } else {
                                fail("scenario " + scenario + " has no world step", null);
                            }
                        }
                    }
                }
            }
            case INVENTORY_SETTLE -> {
                if (phaseFrames >= 30) {
                    requestShot("03_inventory.png", () -> {
                        mc.gui.setScreen(null);
                        enter(Phase.INVENTORY_CLOSE_SETTLE);
                    });
                }
            }
            case INVENTORY_CLOSE_SETTLE -> {
                if (phaseFrames >= 10) {
                    finish();
                }
            }
            case WAIT_FRAMES -> {
                if (frames >= waitUntilFrame) {
                    Runnable r = afterWait;
                    afterWait = null;
                    r.run();
                }
            }
            case ROTATE -> stepRotate();
            case CUSTOM -> custom.onFrame(context);
            case SOAK -> stepSoak();
            case SHOT_WAIT -> stepShotWait();
            case FINISHED -> { }
        }
    }

    /** Hands control to the scenario registered through {@link AutotestScenarios}. */
    private void startCustom() throws Exception {
        enter(Phase.CUSTOM);
        kv("event", "custom_scenario_start", "scenario", scenario, "needsWorld", String.valueOf(custom.needsWorld()));
        custom.run(context);
    }

    /** The {@link AutotestContext} handed to custom scenarios; the callbacks put the harness back into CUSTOM. */
    private final class Ctx implements AutotestContext {
        @Override public Minecraft minecraft() { return mc; }
        @Override public long frames() { return frames; }
        @Override public void log(String... keyValuePairs) { kv(keyValuePairs); }
        @Override public void shot(String fileName, Runnable then) {
            requestShot(fileName, () -> {
                enter(Phase.CUSTOM);
                then.run();
            });
        }
        @Override public void waitFrames(int n, Runnable then) {
            AutotestHarness.this.waitFrames(n, () -> {
                enter(Phase.CUSTOM);
                then.run();
            });
        }
        @Override public void finish() { AutotestHarness.this.finish(); }
        @Override public void fail(String reason, Throwable cause) { AutotestHarness.this.fail(reason, cause); }
    }

    /** Waits a number of rendered frames (camera stays as it is), then runs {@code then}. */
    private void waitFrames(int n, Runnable then) {
        waitUntilFrame = frames + n;
        afterWait = then;
        enter(Phase.WAIT_FRAMES);
    }

    /** Client-side camera pose: base pose plus the rotation offset of the world_rotate scenario. */
    private void applyPose() {
        if (!poseLocked || mc.player == null) {
            return;
        }
        float yaw = poseYaw;
        if (rotating) {
            long rf = Math.min(frames - rotateStartFrame, rotateFrames);
            yaw += rotateDeg * (float) rf / (float) rotateFrames;
        }
        float pitch = posePitch;
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setXRot(pitch);
        mc.player.xRotO = pitch;
    }

    /** world_rotate: a shot every SHOT_GAP_FRAMES rotation frames while the camera keeps turning. */
    private void stepRotate() {
        long rf = frames - rotateStartFrame;
        int due = (int) Math.min(rf / SHOT_GAP_FRAMES, (long) Math.ceil(rotateFrames / (double) SHOT_GAP_FRAMES));
        if (rotateShotsTaken < due) {
            rotateShotsTaken++;
            final int n = rotateShotsTaken;
            final boolean last = n >= (int) Math.ceil(rotateFrames / (double) SHOT_GAP_FRAMES);
            kv(concat(new String[] {"event", "rotate_step", "n", String.valueOf(n), "rotateFrame", String.valueOf(rf)}, poseKv()));
            requestShot(String.format("r_%02d.png", n), last ? this::finish : () -> enter(Phase.ROTATE));
        }
    }

    /** yaw/pitch/position of the player as kv pairs (rotation in degrees). */
    private String[] poseKv() {
        if (mc == null || mc.player == null) {
            return new String[] {"yaw", "n/a", "pitch", "n/a", "x", "n/a", "y", "n/a", "z", "n/a"};
        }
        java.util.Locale l = java.util.Locale.ROOT;
        return new String[] {
            "yaw", String.format(l, "%.2f", mc.player.getYRot()),
            "pitch", String.format(l, "%.2f", mc.player.getXRot()),
            "x", String.format(l, "%.2f", mc.player.getX()),
            "y", String.format(l, "%.2f", mc.player.getY()),
            "z", String.format(l, "%.2f", mc.player.getZ())};
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private void stepSoak() {
        long now = System.currentTimeMillis();
        Screen open = mc.gui.screen();
        if (open != null && mc.level != null && mc.player != null) {
            unexpectedScreens++;
            kv("event", "unexpected_screen", "class", open.getClass().getName(),
                    "count", String.valueOf(unexpectedScreens), "windowActive", String.valueOf(mc.isWindowActive()));
            mc.gui.setScreen(null);
        }
        if (mc.level == null || mc.player == null) {
            Screen cur = mc.gui.screen();
            fail("soak: world lost; screen=" + (cur == null ? "null" : cur.getClass().getName())
                    + " level_null=" + (mc.level == null) + " player_null=" + (mc.player == null)
                    + " windowActive=" + mc.isWindowActive() + " unexpected_screens=" + unexpectedScreens, null);
            return;
        }
        if (now - soakStartMs >= SOAK_DURATION_MS) {
            int n = ++shotIndex;
            kv("event", "soak_tick", "n", String.valueOf(n), "final", "true",
                    "elapsedSec", String.valueOf((now - soakStartMs) / 1000), "fps", String.valueOf(mc.getFps()));
            requestShot(String.format("soak_%02d_final.png", n), this::finish);
        } else if (now - lastSoakShotMs >= SOAK_SHOT_INTERVAL_MS) {
            lastSoakShotMs = now;
            int n = ++shotIndex;
            kv("event", "soak_tick", "n", String.valueOf(n),
                    "elapsedSec", String.valueOf((now - soakStartMs) / 1000), "fps", String.valueOf(mc.getFps()));
            requestShot(String.format("soak_%02d.png", n), () -> enter(Phase.SOAK));
        }
    }

    private void startHeartbeat() {
        Thread t = new Thread(() -> {
            while (!finishing) {
                try {
                    Thread.sleep(HEARTBEAT_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (finishing) {
                    return;
                }
                long sinceFrame = System.currentTimeMillis() - lastFrameMs;
                kv("event", "heartbeat", "scenario", scenario, "phase", phaseName,
                        "frames", String.valueOf(framesSeen), "fps", String.valueOf(lastFps),
                        "msSinceLastFrame", String.valueOf(sinceFrame),
                        "elapsedSec", String.valueOf((System.currentTimeMillis() - startMs) / 1000));
                if (sinceFrame > STALL_MS) {
                    kv("event", "render_stalled", "msSinceLastFrame", String.valueOf(sinceFrame), "phase", phaseName);
                }
            }
        }, "VKB-AUTOTEST-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ world creation / commands

    private void beginWorldCreation(Screen parent) {
        // schedule(): runs in runAllTasks before tick(), not inside renderFrame (report 2.8).
        mc.schedule(() -> {
            try {
                LevelStorageSource source = mc.getLevelSource();
                if (source.levelExists(WORLD_NAME)) {
                    try (var access = source.createAccess(WORLD_NAME)) {
                        access.deleteLevel();
                    }
                    kv("event", "old_world_deleted", "world", WORLD_NAME);
                }
                LevelSettings settings = new LevelSettings(
                        WORLD_NAME,
                        GameType.CREATIVE,
                        new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                        true,
                        WorldDataConfiguration.DEFAULT);
                WorldOptions options = new WorldOptions(WORLD_SEED, false, false);
                kv("event", "creating_world", "world", WORLD_NAME, "seed", String.valueOf(WORLD_SEED));
                mc.createWorldOpenFlows().createFreshLevel(
                        WORLD_NAME, settings, options, WorldPresets::createNormalWorldDimensions, parent);
                // createFreshLevel blocks until the integrated server is ready (report 2.3).
                enter(Phase.WAIT_WORLD);
            } catch (Throwable t) {
                fail("world creation failed: " + t, t);
            }
        });
    }

    private void runWorldCommands() {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server after world load", null);
            return;
        }
        List<String> commands = List.of(
                "gamerule advance_time false",
                "gamerule advance_weather false",
                "gamerule spawn_mobs false",
                "time set noon",
                "weather clear",
                "give @a minecraft:diamond_sword",
                "give @a minecraft:grass_block 64",
                "give @a minecraft:torch 64",
                "give @a minecraft:diamond_pickaxe",
                // fixed camera pose (server side; the client then re-asserts the rotation every frame)
                String.format(java.util.Locale.ROOT, "tp @a %.3f %.3f %.3f %.3f %.3f", poseX, poseY, poseZ, poseYaw, posePitch));
        server.execute(() -> {
            try {
                for (String cmd : commands) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                }
                kv("event", "commands_done", "count", String.valueOf(commands.size()));
            } catch (Throwable t) {
                fail("server command failed: " + t, t);
            }
        });
    }

    // ------------------------------------------------------------------ screenshots

    private void requestShot(String name, Runnable then) {
        try {
            Files.createDirectories(outDir);
            shotPath = outDir.resolve(name);
            Files.deleteIfExists(shotPath);
            shotDone = false;
            shotError.set(null);
            shotRequestFrame = frames;
            afterShot = then;
            final Path target = shotPath;
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                try (NativeImage img = image) {
                    img.writeToFile(target);
                    shotDone = true;
                } catch (Throwable t) {
                    shotError.set(t);
                }
            });
            phase = Phase.SHOT_WAIT;
            phaseStartMs = System.currentTimeMillis();
            kv("event", "shot_requested", "file", name);
        } catch (Throwable t) {
            fail("screenshot request failed: " + t, t);
        }
    }

    private void stepShotWait() throws Exception {
        Throwable err = shotError.get();
        if (err != null) {
            fail("screenshot write failed: " + err, err);
            return;
        }
        // Readback callback arrives ~2 submits later (report 3.4): wait >=3 frames AND the file.
        if (shotDone && frames - shotRequestFrame >= MIN_FRAMES_AFTER_SHOT && Files.isRegularFile(shotPath)) {
            long size = Files.size(shotPath);
            if (size <= 0) {
                fail("screenshot file is empty: " + shotPath, null);
                return;
            }
            kv(concat(new String[] {"event", "shot_saved", "file", shotPath.getFileName().toString(), "bytes", String.valueOf(size),
                    "renderTarget", mc.gameRenderer.mainRenderTarget().width + "x" + mc.gameRenderer.mainRenderTarget().height},
                    poseKv()));
            Runnable next = afterShot;
            afterShot = null;
            next.run();
        } else if (System.currentTimeMillis() - phaseStartMs > 30_000L) {
            fail("timeout waiting for screenshot file " + shotPath, null);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void enter(Phase next) {
        phase = next;
        phaseStartFrame = frames;
        phaseStartMs = System.currentTimeMillis();
    }

    private void logEnvironment() {
        String backend = "unknown";
        String driver = "unknown";
        String device = "unknown";
        String vendor = "unknown";
        try {
            DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
            backend = info.backendName();
            driver = info.driverInfo();
            device = info.name();
            vendor = info.vendorName();
        } catch (Throwable t) {
            LOGGER.error("[VKB-AUTOTEST] could not read DeviceInfo\n{}", LogPaths.stack(t));
            kv("deviceInfoError", LogPaths.show(String.valueOf(t)));
        }
        kv("scenario", scenario,
                "backend", backend,
                "driver", driver,
                "device", device,
                "vendor", vendor,
                "detectorVulkan", String.valueOf(VulkanBackendDetector.isVulkanActive()),
                "window", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight(),
                "guiScale", String.valueOf(mc.getWindow().getGuiScale()),
                "renderTarget", mc.gameRenderer.mainRenderTarget().width + "x" + mc.gameRenderer.mainRenderTarget().height,
                "fps", String.valueOf(mc.getFps()));
    }

    private void finish() {
        if (finishing) {
            return;
        }
        finishing = true;
        phase = Phase.FINISHED;
        kv(concat(new String[] {"scenario", scenario,
                "frames", String.valueOf(frames),
                "fps", String.valueOf(mc.getFps()),
                "window", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight(),
                "renderTarget", mc.gameRenderer.mainRenderTarget().width + "x" + mc.gameRenderer.mainRenderTarget().height,
                "durationSec", String.valueOf((System.currentTimeMillis() - startMs) / 1000),
                "unexpected_screens", String.valueOf(unexpectedScreens)},
                concat(poseKv(), new String[] {"result", "PASS"})));
        mc.stop();
    }

    private void fail(String reason, Throwable t) {
        if (finishing) {
            return;
        }
        finishing = true;
        phase = Phase.FINISHED;
        if (t != null) {
            LOGGER.error("[VKB-AUTOTEST] failure stack trace\n{}", LogPaths.stack(t));
        }
        kv(concat(new String[] {"scenario", scenario, "frames", String.valueOf(frames)},
                concat(poseKv(), new String[] {"result", "FAIL", "error", reason})));
        mc.stop();
    }

    private static void kv(String... pairs) {
        StringBuilder sb = new StringBuilder("[VKB-AUTOTEST]");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String v = LogPaths.show(pairs[i + 1].replace('\n', ' ').replace('\r', ' '));
            if (v.indexOf(' ') >= 0) {
                v = '"' + v.replace("\"", "'") + '"';
            }
            sb.append(' ').append(pairs[i]).append('=').append(v);
        }
        LOGGER.info(sb.toString());
    }
}
