package io.github.andrexarlay.vulkancompat.sodium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Official in-game Development Roadmap for Bedray:
 * displays confirmed target releases, engineering milestones, and timelines.
 */
public final class BedrayRoadmapScreen extends Screen {
    private final Screen parent;
    private int scrollOffset = 0;
    private int maxScroll = 0;

    public record Milestone(
        String version,
        String targetDate,
        String status,
        int statusColor,
        String summary,
        List<String> highlights
    ) {}

    private static final List<Milestone> MILESTONES = List.of(
        new Milestone(
            "v0.1.0-alpha.1",
            "Current Release (October 2026)",
            "RELEASED",
            0xFF55FF55,
            "Hardware Vulkan Ray Tracing & Performance Foundation",
            List.of(
                "Vulkan Ray Query pipeline (Path Tracing & Fast RT modes)",
                "NVIDIA DLSS 3.7 Super Resolution & NVIDIA Reflex Low Latency",
                "NVIDIA Streamline DLSS Frame Generation & AMD FSR 3 Frame Generation",
                "AMD FSR 3.1 & Intel XeSS 1.3 upscaling backends",
                "Physical Water Shading with RT reflections & caustics",
                "Dedicated Bedray Config Screen with comprehensive categories",
                "DLL Natives Manager with auto-extraction & drag-and-drop",
                "Block selection wireframe shadow & light occlusion bypass"
            )
        ),
        new Milestone(
            "v0.2.0-beta",
            "Target: Q4 2026 (Nov - Dec 2026)",
            "IN PROGRESS",
            0xFFFFFF55,
            "ReSTIR Direct Illumination & Advanced PBR Pipeline",
            List.of(
                "ReSTIR DI (Reservoir Spatio-Temporal Importance Resampling) for direct lighting without wall light leakage",
                "Direct import of BetterRTX / Bedrock .rtpack material packages (MER/MERS format)",
                "Physical Volumetric Clouds with Henyey-Greenstein spectral phase scattering",
                "Parallax Occlusion Mapping (POM) with contact self-shadowing",
                "Real-time Gerstner ocean waves with physically simulated normal ripples",
                "Subsurface scattering (SSS) transmission for foliage, wax and ice"
            )
        ),
        new Milestone(
            "v0.3.0",
            "Target: Q1 2027 (Jan - Mar 2027)",
            "COMMITTED",
            0xFF55FFFF,
            "Iris Shaderpack Compatibility & Next-Gen AI Upscaling",
            List.of(
                "Iris Dual-Path (I0-I6): Run OptiFine / Iris shaderpacks on Vulkan with SPIR-V translation",
                "NVIDIA DLSS 5.x & Neural Rendering SDK integration (slot reserved in Natives Manager)",
                "AMD FSR 4 AI Neural Upscaler & Intel XeSS 2.0 Frame Generation (slot reserved)",
                "NVIDIA NRC (Neural Radiance Caching) for instant real-time noise-free path tracing convergence",
                "Moving Contraptions RT: Dynamic TLAS updates for Create Mod moving contraptions",
                "DirectSR / Vulkan Cross-Vendor Super Resolution unified API"
            )
        ),
        new Milestone(
            "v1.0.0",
            "Target: Q2 2027 (Apr - Jun 2027)",
            "PLANNED",
            0xFFD080FF,
            "Production Enterprise Release & Full Physics Parity",
            List.of(
                "Multi-Loader Parity: 100% feature synchronization between NeoForge and Fabric",
                "Physics Engine Synchronization: Zero-latency TLAS for Valkyrien Skies / Create Aeronautics",
                "Integrated GPU Ray Tracing Performance Benchmark & Frametime Profiler",
                "Automated Community Resource Pack PBR Converter",
                "Full headless dedicated server verification"
            )
        )
    );

    public BedrayRoadmapScreen(Screen parent) {
        super(Component.translatableWithFallback("bedray.roadmap.title", "Bedray Development Roadmap"));
        this.parent = parent;
    }

    private static Component tr(String k, String f, Object... a) {
        return Component.translatableWithFallback(k, f, a);
    }

    @Override
    protected void init() {
        int w = Math.min(width - 16, 460);
        int x = (width - w) / 2;
        int bottom = height - 28;

        int btnW = (w - 12) / 3;

        // Button 1: Open Config
        addRenderableWidget(Button.builder(tr("bedray.roadmap.open_config", "Mod Config"), b -> {
            Minecraft.getInstance().gui.setScreen(new BedrayConfigScreen(parent));
        }).bounds(x, bottom, btnW, 20).build());

        // Button 2: Open Natives Manager
        addRenderableWidget(Button.builder(tr("bedray.roadmap.open_natives", "DLL Natives"), b -> {
            Minecraft.getInstance().gui.setScreen(new NativesManagerScreen(this));
        }).bounds(x + btnW + 6, bottom, btnW, 20).build());

        // Button 3: Done
        addRenderableWidget(Button.builder(tr("gui.done", "Done"), b -> onClose())
            .bounds(x + (btnW + 6) * 2, bottom, btnW, 20)
            .build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0 && mouseY >= 36 && mouseY < height - 46) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * 24.0)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);

        // Header Title
        g.text(font, title, (width - font.width(title)) / 2, 8, 0xFFFFFFFF);

        // Subtitle
        Component sub = tr("bedray.roadmap.subtitle", "Confirmed Engineering Milestones & Target Timelines (2026 - 2027)");
        g.text(font, sub, (width - font.width(sub)) / 2, 22, 0xFF80D0FF);

        int cardW = Math.min(width - 16, 460);
        int cardX = (width - cardW) / 2;
        int listTop = 36;
        int listBottom = height - 46;
        int textWidth = Math.max(1, cardW - 24);
        int[] cardHeights = new int[MILESTONES.size()];
        int totalContentH = 0;
        for (int i = 0; i < MILESTONES.size(); i++) {
            Milestone milestone = MILESTONES.get(i);
            int cardH = 34 + font.split(Component.literal(milestone.summary), textWidth).size() * 11;
            for (String highlight : milestone.highlights) {
                cardH += font.split(Component.literal("• " + highlight), textWidth).size() * 11;
            }
            cardHeights[i] = cardH + 8;
            totalContentH += cardHeights[i] + 8;
        }
        int viewableH = Math.max(0, listBottom - listTop);
        maxScroll = Math.max(0, totalContentH - viewableH);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));

        int y = listTop - scrollOffset;
        g.enableScissor(cardX, listTop, cardX + cardW, Math.max(listTop, listBottom));
        for (int index = 0; index < MILESTONES.size(); index++) {
            Milestone m = MILESTONES.get(index);
            int cardH = cardHeights[index];

            if (y + cardH >= listTop && y <= listBottom) {
                // Card background
                g.fill(cardX, y, cardX + cardW, y + cardH, 0x90101018);
                // Border
                g.fill(cardX, y, cardX + cardW, y + 1, 0xFF353545);
                g.fill(cardX, y + cardH - 1, cardX + cardW, y + cardH, 0xFF202028);
                g.fill(cardX, y, cardX + 1, y + cardH, 0xFF353545);
                g.fill(cardX + cardW - 1, y, cardX + cardW, y + cardH, 0xFF202028);

                // Left accent bar
                g.fill(cardX + 1, y + 1, cardX + 4, y + cardH - 1, m.statusColor);

                // Version & Date
                g.text(font, m.version, cardX + 8, y + 6, 0xFFFFFFFF);
                g.text(font, font.plainSubstrByWidth(m.targetDate, textWidth), cardX + 8, y + 18, 0xFFAAAAAA);

                // Status Badge
                String badge = "[ " + m.status + " ]";
                int bw = font.width(badge);
                g.text(font, badge, cardX + cardW - bw - 8, y + 6, m.statusColor);

                // Summary
                int itemY = y + 32;
                for (var line : font.split(Component.literal(m.summary), textWidth)) {
                    g.text(font, line, cardX + 8, itemY, 0xFFE0E0A0);
                    itemY += 11;
                }

                // Bullets
                for (String h : m.highlights) {
                    for (var line : font.split(Component.literal("• " + h), textWidth)) {
                        g.text(font, line, cardX + 12, itemY, 0xFFCCCCCC);
                        itemY += 11;
                    }
                }
            }

            y += cardH + 8;
        }

        g.disableScissor();

        // Scroll hint if scrollable
        if (maxScroll > 0) {
            String scrollHint = "(Scroll mouse wheel to view more milestones)";
            g.text(font, scrollHint, (width - font.width(scrollHint)) / 2, height - 41, 0x80AAAAAA);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
