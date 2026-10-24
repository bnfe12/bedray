package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rt.world.RtAtmosphereSettings;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.TextureLodBias;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated In-Game Configuration Screen for Bedray Ray Tracing & Performance.
 * Provides deep global configuration across 7 organized categories with smooth scrolling.
 */
public final class BedrayConfigScreen extends Screen {
    private final Screen parent;
    private int selectedCategory = 0;
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private int listTop = 48;

    private static final String[] CATEGORIES = {
        "Ray Tracing",
        "Shadows & Light",
        "Nature & Water",
        "Upscalers & FG",
        "Camera & Color",
        "Materials & PBR",
        "System & Future"
    };

    private static final class OptionRow {
        final String label;
        final String hint;
        final java.util.function.Supplier<String> valueText;
        final Runnable onClick;

        OptionRow(String label, String hint, java.util.function.Supplier<String> valueText, Runnable onClick) {
            this.label = label;
            this.hint = hint;
            this.valueText = valueText;
            this.onClick = onClick;
        }
    }

    private final List<OptionRow> currentRows = new ArrayList<>();
    private final List<Button> rowButtons = new ArrayList<>();

    public BedrayConfigScreen(Screen parent) {
        super(Component.translatableWithFallback("bedray.config.title", "Bedray Ray Tracing Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        currentRows.clear();
        rowButtons.clear();
        scrollOffset = 0;

        int contentWidth = Math.min(width - 16, 540);
        int contentX = (width - contentWidth) / 2;

        // Top Category Tabs
        int tabCount = CATEGORIES.length;
        int columns = Math.max(1, Math.min(tabCount, contentWidth / 100));
        int tabW = (contentWidth - (columns - 1) * 3) / columns;
        listTop = 22 + ((tabCount + columns - 1) / columns) * 21 + 8;
        for (int i = 0; i < tabCount; i++) {
            final int catIdx = i;
            boolean active = (i == selectedCategory);
            String title = (active ? "> " : "") + CATEGORIES[i] + (active ? " <" : "");
            addRenderableWidget(Button.builder(Component.literal(title), b -> {
                selectedCategory = catIdx;
                rebuildWidgets();
            }).bounds(contentX + (i % columns) * (tabW + 3), 22 + (i / columns) * 21, tabW, 18).build());
        }

        // Build items for current category
        buildCategoryRows();

        // Render option rows in center scrollable area
        int startY = listTop;
        int rowH = 24;
        int btnW = 150;
        int btnX = contentX + contentWidth - btnW;

        for (int i = 0; i < currentRows.size(); i++) {
            OptionRow row = currentRows.get(i);
            int rowY = startY + i * rowH;
            Button btn = Button.builder(Component.literal(row.valueText.get()), b -> {
                row.onClick.run();
                // Presets and resets can change several rows at once.
                for (int index = 0; index < rowButtons.size(); index++) {
                    OptionRow changed = currentRows.get(index);
                    String value = changed.valueText.get();
                    rowButtons.get(index).setMessage(Component.literal(value));
                    rowButtons.get(index).setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(changed.hint + "\n" + value)));
                }
            }).bounds(btnX, rowY, btnW, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(row.hint + "\n" + row.valueText.get())))
                .build();

            rowButtons.add(btn);
            addRenderableWidget(btn);
        }
        updateRowButtonPositions();

        // Bottom action buttons
        int bottomY = height - 26;
        int bottomBtnW = (contentWidth - 16) / 5;

        // Button 1: Roadmap
        addRenderableWidget(Button.builder(Component.literal("Roadmap..."), b -> {
            Minecraft.getInstance().gui.setScreen(new BedrayRoadmapScreen(this));
        }).bounds(contentX, bottomY, bottomBtnW, 20).build());

        // Button 2: Presets
        addRenderableWidget(Button.builder(Component.literal("My Presets..."), b -> {
            Minecraft.getInstance().gui.setScreen(new PresetsScreen(this));
        }).bounds(contentX + bottomBtnW + 4, bottomY, bottomBtnW, 20).build());

        // Button 3: Natives
        addRenderableWidget(Button.builder(Component.literal("DLL Natives..."), b -> {
            Minecraft.getInstance().gui.setScreen(new NativesManagerScreen(this));
        }).bounds(contentX + (bottomBtnW + 4) * 2, bottomY, bottomBtnW, 20).build());

        // Button 4: Sodium
        addRenderableWidget(Button.builder(Component.literal("Sodium..."), b -> {
            openSodiumScreen();
        }).bounds(contentX + (bottomBtnW + 4) * 3, bottomY, bottomBtnW, 20).build());

        // Button 5: Done
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(contentX + (bottomBtnW + 4) * 4, bottomY, bottomBtnW, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (selectedCategory == 0) {
            // These values change on the render thread after a request, without another settings click.
            for (int index = 0; index < rowButtons.size(); index++) {
                String text = currentRows.get(index).valueText.get();
                Button button = rowButtons.get(index);
                if (!button.getMessage().getString().equals(text)) {
                    button.setMessage(Component.literal(text));
                    button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(currentRows.get(index).hint + "\n" + text)));
                }
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0 && mouseY >= listTop && mouseY < height - 42) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * 24.0)));
            updateRowButtonPositions();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void updateRowButtonPositions() {
        int startY = listTop;
        int listBottom = height - 42;
        int rowH = 24;

        for (int i = 0; i < rowButtons.size(); i++) {
            Button btn = rowButtons.get(i);
            int ry = startY + i * rowH - scrollOffset;
            btn.setY(ry);
            btn.visible = (ry >= startY && ry + 20 <= listBottom);
        }
    }

    private void buildCategoryRows() {
        switch (selectedCategory) {
            case 0 -> { // Ray Tracing & Engine
                currentRows.add(new OptionRow("Iris Vulkan (Experimental)", "Post-only GLSL compatibility host. General shaderpack support is still in development.",
                    io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisRenderBridge::runtimeStatus, () -> {}));
                currentRows.add(new OptionRow(text("bedray.opt.renderer", "World Renderer"),
                    text("bedray.opt.renderer.desc", "Hardware RT or experimental voxel diagnostics. Voxel requires the Bedray Compute add-on; it uses bilinear scaling and disables frame generation."),
                    () -> VulkanSodiumConfig.rendererName(VulkanRtxConfig.selectedRenderer()).getString(),
                    () -> {
                        VulkanSodiumConfig.applyRendererFromUi(next(VulkanRtxConfig.selectedRenderer(), VulkanRtxConfig.RendererSelection.values()));
                        VulkanRtxConfig.save();
                    }));
                currentRows.add(new OptionRow(text("bedray.opt.renderer_status", "Renderer Status"),
                    text("bedray.opt.renderer_status.desc", "Reports the requested renderer and host command recording. A recorded frame does not establish gameplay compatibility."),
                    VulkanSodiumConfig::rendererStatus, () -> {}));
                currentRows.add(new OptionRow(text("bedray.compute.data", "Compute Scene Data"),
                    text("bedray.compute.data.desc", "A 48-block window of loaded sections. Unsupported shapes and textures are omitted."),
                    () -> GraphicsBackendRegistry.backend()==null
                        ? text("bedray.renderer.status.missing", "Unavailable: install the matching Bedray Compute add-on")
                        : GraphicsBackendRegistry.backend().sceneDetail(), () -> {}));
                if (SettingsCatalog.expertUi()) {
                    currentRows.add(new OptionRow(text("bedray.opt.compute_scene", "Compute Diagnostic Scene"),
                        text("bedray.opt.compute_scene.desc", "Session diagnostic scene for the selected voxel renderer. Synthetic uses a fixed test scene; live mode draws opaque occupancy."),
                        () -> VulkanRtxConfig.voxelRendererSelected()
                            ? VulkanSodiumConfig.computeSceneName(VulkanRtxConfig.computeDiagnosticScene()).getString()
                            : text("bedray.compute.select_voxel", "Select Voxel first"),
                        () -> {
                            if (!VulkanRtxConfig.voxelRendererSelected()) return;
                            GraphicsBackendRegistry.SceneMode[] scenes = {GraphicsBackendRegistry.SceneMode.SYNTHETIC, GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE};
                            VulkanSodiumConfig.applyComputeSceneFromUi(next(VulkanRtxConfig.computeDiagnosticScene(), scenes));
                            VulkanRtxConfig.save();
                        }));
                    currentRows.add(new OptionRow(text("bedray.opt.compute_debug_view", "Compute Debug View"),
                        text("bedray.opt.compute_debug_view.desc", "Inspect opaque occupancy shading, normals, ray distance or unsupported and missing cells."),
                        () -> text("bedray.compute.view." + GraphicsBackendRegistry.debugView().name().toLowerCase(java.util.Locale.ROOT), GraphicsBackendRegistry.debugView().name()),
                        () -> {
                            if (GraphicsBackendRegistry.backend()==null || !GraphicsBackendRegistry.backend().supportsDebugViews()) return;
                            GraphicsBackendRegistry.setDebugView(next(GraphicsBackendRegistry.debugView(), GraphicsBackendRegistry.DebugView.values()));
                        }));
                }

                currentRows.add(new OptionRow("Ray Tracing Mode", "Master renderer switch (Off, Fast Ray Tracing, Path Tracing)",
                    () -> {
                        if (VulkanRtxConfig.voxelRendererSelected()) return text("bedray.renderer.hardware_inactive", "Saved hardware mode (inactive)");
                        RtMode m = VulkanRtxConfig.rtMode;
                        if (m == RtMode.OFF) return "Off";
                        if (m == RtMode.PATH_TRACED) return "Path Tracing (Full)";
                        if (m == RtMode.RAY_TRACED) return "Ray Tracing (Direct+GI)";
                        return m.logName().toUpperCase(java.util.Locale.ROOT);
                    },
                    () -> {
                        if (VulkanRtxConfig.voxelRendererSelected()) return;
                        RtMode[] modes = VulkanRtxConfig.raysUnlocked()
                            ? new RtMode[]{RtMode.OFF, RtMode.PATH_TRACED, RtMode.RAY_TRACED}
                            : new RtMode[]{RtMode.OFF, RtMode.PATH_TRACED};
                        VulkanSodiumConfig.applyRtModeFromUi(next(VulkanRtxConfig.rtMode, modes));
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("RT Quality Preset", "Overall quality profile for lighting and ray tracing",
                    () -> RtSettings.preset != null ? RtSettings.preset.name() : "CUSTOM",
                    () -> {
                        RtSettings.Preset[] p = {RtSettings.Preset.LOW, RtSettings.Preset.MEDIUM, RtSettings.Preset.HIGH,
                            RtSettings.Preset.ULTRA, RtSettings.Preset.FAST, RtSettings.Preset.QUALITY};
                        RtSettings.Preset nextPreset = next(RtSettings.preset != null ? RtSettings.preset : RtSettings.Preset.MEDIUM, p);
                        RtSettings.applyPreset(nextPreset);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Ray Tracing Distance", "World block distance for primary and secondary ray queries",
                    () -> (RtSettings.rtDistanceChunks * 16) + " blocks",
                    () -> {
                        int[] chunks = {4, 6, 8, 10, 12, 16, 20, 24, 32};
                        RtSettings.rtDistanceChunks = next(RtSettings.rtDistanceChunks, chunks);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Path Max Bounces", "Maximum light bounces in full Path Tracing mode",
                    () -> RtSettings.pathBounces + " Bounces",
                    () -> {
                        int[] b = {1, 2, 3, 4, 6, 8};
                        RtSettings.pathBounces = next(RtSettings.pathBounces, b);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Samples Per Pixel (SPP)", "Primary ray samples per pixel per frame",
                    () -> RtSettings.pathSpp + " SPP",
                    () -> {
                        int[] s = {1, 2, 4, 8, 16};
                        RtSettings.pathSpp = next(RtSettings.pathSpp, s);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Path Denoiser", "Spatiotemporal AI / bilateral filtering for path tracing",
                    () -> RtSettings.pathDenoiser.name(),
                    () -> {
                        RtSettings.pathDenoiser = next(RtSettings.pathDenoiser, RtSettings.PathDenoiser.values());
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Demodulation & Accumulation", "Separate albedo and irradiance during denoising",
                    () -> RtSettings.pathAccumulate ? "ON" : "OFF",
                    () -> {
                        RtSettings.pathAccumulate = !RtSettings.pathAccumulate;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("DLSS-RR Geo Normals", "Feed flat voxel geometric normals (ng) to DLSS-RR for sharp block edges",
                    () -> RtSettings.dlssRrGeometricNormals ? "ON" : "OFF",
                    () -> {
                        RtSettings.dlssRrGeometricNormals = !RtSettings.dlssRrGeometricNormals;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("VRAM Budget (MB)", "GPU memory ceiling for Acceleration Structures (TLAS/BLAS)",
                    () -> RtSettings.vramBudgetMb + " MB",
                    () -> {
                        int[] budgets = {1024, 1536, 2048, 3072, 4096, 6144, 8192};
                        RtSettings.vramBudgetMb = next(RtSettings.vramBudgetMb, budgets);
                        VulkanRtxConfig.save();
                    }));
            }
            case 1 -> { // Shadows & Light
                currentRows.add(new OptionRow("Traced Sun Shadows", "Physically accurate direct solar and lunar ray shadows",
                    () -> RtSettings.raysShadows ? "ON" : "OFF",
                    () -> {
                        RtSettings.raysShadows = !RtSettings.raysShadows;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Soft Penumbra Shadows", "Widened solar disk with realistic soft penumbra edges",
                    () -> RtSettings.raysSoftShadows ? "ON" : "OFF",
                    () -> {
                        RtSettings.raysSoftShadows = !RtSettings.raysSoftShadows;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Sun Disk Angular Size", "Apparent solar disk size controlling shadow softness",
                    () -> String.format(java.util.Locale.US, "%.1f°", RtSettings.sunSizeTenths / 10.0f),
                    () -> {
                        int[] sizes = {5, 10, 14, 20, 30};
                        RtSettings.sunSizeTenths = next(RtSettings.sunSizeTenths, sizes);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Shadow Ray Count", "Rays per pixel for soft shadow penumbra reconstruction",
                    () -> RtSettings.raysShadowQuality + " spp",
                    () -> {
                        int[] q = {1, 2, 4, 8};
                        RtSettings.raysShadowQuality = next(RtSettings.raysShadowQuality, q);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Ray Traced AO (RTAO)", "Physically traced ambient contact shadows",
                    () -> RtSettings.raysAo ? "ON" : "OFF",
                    () -> {
                        RtSettings.raysAo = !RtSettings.raysAo;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("RTAO Sample Radius", "Search sphere radius for ambient occlusion contact shadow",
                    () -> RtSettings.raysAoRadius + " Blocks",
                    () -> {
                        int[] rad = {1, 2, 3, 4, 5, 6};
                        RtSettings.raysAoRadius = next(RtSettings.raysAoRadius, rad);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Direct GI Bounce", "One-bounce diffuse global illumination from sun and sky",
                    () -> RtSettings.raysGi ? "ON" : "OFF",
                    () -> {
                        RtSettings.raysGi = !RtSettings.raysGi;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Emissive Block Lighting", "Torches, lanterns, glowstone and lava cast real light",
                    () -> RtSettings.raysEmissive ? "ON" : "OFF",
                    () -> {
                        RtSettings.raysEmissive = !RtSettings.raysEmissive;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Emissive Distance", "Effective light reach for voxel emissives in blocks",
                    () -> RtSettings.raysEmissiveDistance + " Blocks",
                    () -> {
                        int[] dist = {16, 24, 32, 48, 64};
                        RtSettings.raysEmissiveDistance = next(RtSettings.raysEmissiveDistance, dist);
                        VulkanRtxConfig.save();
                    }));
            }
            case 2 -> { // Nature & Water
                currentRows.add(new OptionRow("Waving Foliage & Plants", "Grass, crops and leaves wind swaying (Disabled by default)",
                    () -> RtSettings.wavingFoliage ? "ON" : "OFF",
                    () -> {
                        RtSettings.wavingFoliage = !RtSettings.wavingFoliage;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Wind Strength", "Global wind velocity and foliage sway amplitude",
                    () -> RtSettings.wavingFoliageStrength + "%",
                    () -> {
                        int[] strengths = {25, 50, 75, 100, 125, 150, 200};
                        RtSettings.wavingFoliageStrength = next(RtSettings.wavingFoliageStrength, strengths);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Water Shader Style", "Vanilla flat water or Realistic RTX physical water",
                    () -> RtSettings.waterStyle == RtSettings.WATER_STYLE_REALISTIC ? "Realistic RTX" : "Vanilla Style",
                    () -> {
                        RtSettings.waterStyle = RtSettings.waterStyle == RtSettings.WATER_STYLE_REALISTIC ? RtSettings.WATER_STYLE_VANILLA : RtSettings.WATER_STYLE_REALISTIC;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Water Caustics", "Physically focused sunlight patterns on river and sea beds",
                    () -> RtSettings.caustics ? "ON" : "OFF",
                    () -> {
                        RtSettings.caustics = !RtSettings.caustics;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Volumetric God Rays", "Atmospheric sun crepuscular rays through tree canopies",
                    () -> RtAtmosphereSettings.godRays() != RtAtmosphereSettings.GodRays.OFF ? "ON" : "OFF",
                    () -> {
                        RtAtmosphereSettings.set("godrays", RtAtmosphereSettings.godRays() == RtAtmosphereSettings.GodRays.OFF ? 1 : 0);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Solar In-Scattering", "Atmospheric Rayleigh and Mie light scattering",
                    () -> (int) (RtSettings.fogInScattering * 100) + "%",
                    () -> {
                        float[] s = {0.0f, 0.5f, 1.0f, 1.5f, 2.0f};
                        RtSettings.fogInScattering = next(RtSettings.fogInScattering, s);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Sun Glare", "Anamorphic solar glare when looking towards the sun",
                    () -> RtAtmosphereSettings.sunGlare() ? "ON" : "OFF",
                    () -> {
                        RtAtmosphereSettings.set("sunglare", RtAtmosphereSettings.sunGlare() ? 0 : 1);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Bloom & Glare Glow", "HDR bloom radiance around bright lights and sun",
                    () -> RtAtmosphereSettings.bloom() ? "ON" : "OFF",
                    () -> {
                        RtAtmosphereSettings.set("bloom", RtAtmosphereSettings.bloom() ? 0 : 1);
                        VulkanRtxConfig.save();
                    }));
            }
            case 3 -> { // Upscalers & FG
                currentRows.add(new OptionRow("Active Upscaler", "Super-resolution backend: DLSS, FSR, XeSS, NIS, Bilinear",
                    () -> VulkanRtxConfig.activeBackend.name(),
                    () -> {
                        UpscalerBackend[] b = {UpscalerBackend.BILINEAR, UpscalerBackend.FSR, UpscalerBackend.DLSS,
                            UpscalerBackend.XESS, UpscalerBackend.FSR1, UpscalerBackend.NIS};
                        VulkanRtxConfig.activeBackend = next(VulkanRtxConfig.activeBackend, b);
                        VulkanRtxConfig.reapplyUpscaler("user_config");
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Upscaler Quality Mode", "Resolution scaling preset (Quality, Balanced, Performance)",
                    () -> {
                        UpscalerMode m = VulkanRtxConfig.modeOf(VulkanRtxConfig.activeBackend);
                        return m != null ? m.name() : "N/A (Bilinear)";
                    },
                    () -> {
                        List<UpscalerMode> modes = UpscalerModes.modesFor(VulkanRtxConfig.activeBackend);
                        if (modes.isEmpty()) return;
                        UpscalerMode cur = VulkanRtxConfig.modeOf(VulkanRtxConfig.activeBackend);
                        int idx = modes.indexOf(cur);
                        UpscalerMode nextMode = (idx < 0 || idx + 1 >= modes.size()) ? modes.get(0) : modes.get(idx + 1);
                        switch (VulkanRtxConfig.activeBackend) {
                            case DLSS -> VulkanRtxConfig.dlssMode = nextMode;
                            case XESS -> VulkanRtxConfig.xessMode = nextMode;
                            case FSR -> VulkanRtxConfig.fsrMode = nextMode;
                            case FSR1 -> VulkanRtxConfig.fsr1Mode = nextMode;
                        }
                        VulkanRtxConfig.reapplyUpscaler("user_mode");
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Custom Render Scale", "Custom resolution scaling percentage",
                    () -> VulkanRtxConfig.customScalePercent + "%",
                    () -> {
                        int[] scales = {33, 50, 67, 75, 85, 100};
                        VulkanRtxConfig.customScalePercent = next(VulkanRtxConfig.customScalePercent, scales);
                        VulkanRtxConfig.reapplyUpscaler("user_custom_scale");
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Sharpening Filter", "FidelityFX RCAS or NVIDIA Image Scaling sharpening strength",
                    () -> (int) (VulkanRtxConfig.fsrSharpness * 100) + "%",
                    () -> {
                        float[] sharp = {0.0f, 0.15f, 0.30f, 0.50f, 0.75f, 1.0f};
                        VulkanRtxConfig.fsrSharpness = next(VulkanRtxConfig.fsrSharpness, sharp);
                        VulkanRtxConfig.nisSharpness = VulkanRtxConfig.fsrSharpness;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Frame Generation", "NVIDIA Streamline DLSS-G / AMD FSR 3 frame generator. Disabled for voxel diagnostics.",
                    () -> FrameGenSettings.mode().name(),
                    () -> {
                        if (VulkanRtxConfig.voxelRendererSelected()) return;
                        FrameGenOptions.UiMode current = FrameGenOptions.UiMode.valueOf(FrameGenSettings.mode().name());
                        FrameGenOptions.UiMode[] modes = FrameGenOptions.allowedModes().toArray(FrameGenOptions.UiMode[]::new);
                        FrameGenSettings.setMode(FrameGenSettings.Mode.valueOf(next(current, modes).name()));
                        VulkanRtxConfig.save();
                    }));
            }
            case 4 -> { // Camera & Color
                currentRows.add(new OptionRow("Auto Texture LOD Bias", "Automatically adjust mipmap sharpness based on scale factor",
                    () -> VulkanRtxConfig.autoTextureLodBias ? "ON" : "OFF",
                    () -> {
                        VulkanRtxConfig.autoTextureLodBias = !VulkanRtxConfig.autoTextureLodBias;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Texture LOD Bias Offset", "Manual mipmap bias offset for pixel-art clarity",
                    () -> String.format(java.util.Locale.US, "%+.2f", VulkanRtxConfig.textureLodBiasOffset),
                    () -> {
                        float[] offsets = {-1.0f, -0.75f, -0.5f, -0.25f, 0.0f, +0.25f, +0.5f};
                        VulkanRtxConfig.textureLodBiasOffset = next(VulkanRtxConfig.textureLodBiasOffset, offsets);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Point Sampling Sharpness", "Preserve crisp retro texel grid for pixel-art textures",
                    () -> VulkanRtxConfig.pointSamplingSharpness ? "ON" : "OFF",
                    () -> {
                        VulkanRtxConfig.pointSamplingSharpness = !VulkanRtxConfig.pointSamplingSharpness;
                        VulkanRtxConfig.save();
                    }));
            }
            case 5 -> { // Materials & PBR
                currentRows.add(new OptionRow("Auto Normal Maps", "Generate synthetic microfacet normal maps for vanilla blocks",
                    () -> RtSettings.autoNormals ? "ON" : "OFF",
                    () -> {
                        RtSettings.autoNormals = !RtSettings.autoNormals;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Auto Normal Strength", "Depth and relief intensity of procedural normal maps",
                    () -> RtSettings.autoNormalStrength + "%",
                    () -> {
                        int[] s = {25, 50, 75, 100, 125, 150, 200};
                        RtSettings.autoNormalStrength = next(RtSettings.autoNormalStrength, s);
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Resource Pack Resolution", "Atlas tile dimension matching installed PBR pack",
                    () -> VulkanRtxConfig.resourcePackResolution + "x",
                    () -> {
                        int[] res = {16, 32, 64, 128, 256, 512};
                        VulkanRtxConfig.resourcePackResolution = next(VulkanRtxConfig.resourcePackResolution, res);
                        TextureLodBias.resourcePackResolution = VulkanRtxConfig.resourcePackResolution;
                        VulkanRtxConfig.save();
                    }));
            }
            case 6 -> { // System & Future
                currentRows.add(new OptionRow("Experimental Rays Mode", "Unlock hidden Fast RT and experimental modes",
                    () -> VulkanRtxConfig.experimentalRays ? "UNLOCKED" : "LOCKED",
                    () -> {
                        VulkanRtxConfig.experimentalRays = !VulkanRtxConfig.experimentalRays;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Expert & Debug UI", "Display diagnostic graphs and low-level shader options",
                    () -> VulkanRtxConfig.uiExpert ? "ON" : "OFF",
                    () -> {
                        VulkanRtxConfig.uiExpert = !VulkanRtxConfig.uiExpert;
                        VulkanRtxConfig.save();
                    }));

                currentRows.add(new OptionRow("Reset All To Defaults", "Restore all graphics, ray tracing and upscaler defaults",
                    () -> "Reset",
                    () -> {
                        VulkanSodiumConfig.resetAllToDefaultsAndReopen(this);
                        rebuildWidgets();
                    }));
            }
        }

        // Calculate max vertical scroll based on current rows
        int startY = listTop;
        int listBottom = height - 42;
        int rowH = 24;
        int totalH = currentRows.size() * rowH;
        maxScroll = Math.max(0, totalH - Math.max(0, listBottom - startY));
    }

    private static <T> T next(T current, T[] array) {
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(current)) {
                return array[(i + 1) % array.length];
            }
        }
        return array[0];
    }

    private static String text(String key, String fallback) {
        return Component.translatableWithFallback(key, fallback).getString();
    }

    private static int next(int current, int[] array) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == current) {
                return array[(i + 1) % array.length];
            }
        }
        return array[0];
    }

    private static float next(float current, float[] array) {
        for (int i = 0; i < array.length; i++) {
            if (Math.abs(array[i] - current) < 1.0e-4) {
                return array[(i + 1) % array.length];
            }
        }
        return array[0];
    }

    private void openSodiumScreen() {
        net.caffeinemc.mods.sodium.client.config.structure.OptionPage targetPage = null;
        try {
            for (var mo : net.caffeinemc.mods.sodium.client.config.ConfigManager.CONFIG.getModOptions()) {
                if ("bedray".equals(mo.configId()) && !mo.pages().isEmpty() && mo.pages().get(0) instanceof net.caffeinemc.mods.sodium.client.config.structure.OptionPage page) {
                    targetPage = page;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        if (targetPage != null) {
            Minecraft.getInstance().gui.setScreen(net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen.createScreen(this, targetPage));
        } else {
            Minecraft.getInstance().gui.setScreen(net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen.createScreen(this));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);

        // Header Title
        g.text(font, title, (width - font.width(title)) / 2, 8, 0xFFFFFFFF);

        int contentWidth = Math.min(width - 16, 540);
        int contentX = (width - contentWidth) / 2;

        int startY = listTop;
        int listBottom = height - 42;
        int rowH = 24;

        // Render option text labels and hints
        for (int i = 0; i < currentRows.size(); i++) {
            OptionRow row = currentRows.get(i);
            int rowY = startY + i * rowH - scrollOffset;
            if (rowY < startY || rowY + 20 > listBottom) {
                continue;
            }

            g.text(font, font.plainSubstrByWidth(row.label, contentWidth - 160), contentX + 4, rowY + 2, 0xFFFFFFFF);
            g.text(font, font.plainSubstrByWidth(row.hint, contentWidth - 160), contentX + 4, rowY + 12, 0xFF888888);
        }

        // Scroll indicator if scrollable
        if (maxScroll > 0) {
            String scrollHint = "(Scroll mouse wheel to see more options)";
            g.text(font, scrollHint, (width - font.width(scrollHint)) / 2, height - 39, 0x80888888);
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }
}
