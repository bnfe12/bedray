package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.core.NativeBundle;
import io.github.andrexarlay.vulkanbridge.core.NativeCatalog;
import io.github.andrexarlay.vulkanbridge.core.NativeInstaller;
import io.github.andrexarlay.vulkanbridge.core.NativeScanner;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerLibraryLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Screen for managing native vendor upscaler and frame generation libraries (FSR 3.1, DLSS, XeSS, Streamline, NRD).
 * Provides an Iris/OptiFine-like user experience backed by {@link NativeCatalog}, {@link NativeScanner}, and {@link NativeInstaller}:
 * - Real-time status display of each vendor library (including bundled status for FSR 3.1 and XeSS).
 * - One-click "Open folder" button to open Explorer in the natives directory.
 * - Drag-and-drop support: drop any .zip (e.g. bedray-natives.zip) or .dll directly into this window to install.
 */
public final class NativesManagerScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/NativesUI");

    private final Screen parent;
    private Component status = Component.empty();
    private int statusColor = 0xFFFFD866;

    private static final class LibInfo {
        final String name;
        final String fileName;
        final String desc;
        final boolean installed;
        final boolean bundled;
        final boolean invalid;
        final boolean upcoming;
        final boolean upcomingPresent;

        LibInfo(String name, String fileName, String desc, boolean installed, boolean bundled, boolean invalid,
                boolean upcoming, boolean upcomingPresent) {
            this.name = name;
            this.fileName = fileName;
            this.desc = desc;
            this.installed = installed;
            this.bundled = bundled;
            this.invalid = invalid;
            this.upcoming = upcoming;
            this.upcomingPresent = upcomingPresent;
        }
    }

    private final List<LibInfo> libraries = new ArrayList<>();

    public NativesManagerScreen(Screen parent) {
        super(tr("bedray.natives.title", "Bedray Upscaler & Frame Gen Libraries"));
        this.parent = parent;
    }

    private static Component tr(String k, String f, Object... a) {
        return Component.translatableWithFallback(k, f, a);
    }

    private static Path resolveNativesDir() {
        try {
            return VulkanBridge.libraries().nativesDirectory();
        } catch (Throwable ignored) {
            return UpscalerLibraryLoader.getNativesDirectory();
        }
    }

    private int scrollOffset = 0;
    private int maxScroll = 0;

    private void refreshStatus() {
        libraries.clear();
        Path dir = resolveNativesDir();

        // Ensure bundled DLLs are extracted if missing
        try {
            NativeBundle.extractAll(dir);
        } catch (Throwable ignored) {
        }

        List<NativeScanner.GroupState> groups = NativeScanner.scan(dir, List.of());
        for (NativeScanner.GroupState gs : groups) {
            NativeCatalog.Group g = gs.group();
            if ("wrappers".equals(g.id())) {
                continue;
            }
            List<String> files = new ArrayList<>();
            boolean bundled = false;
            boolean isUpcomingGroup = false;
            boolean anyUpcomingPresent = false;
            for (NativeScanner.SlotState ss : gs.slots()) {
                if (ss.slot().kind() == NativeCatalog.Kind.UPCOMING) {
                    isUpcomingGroup = true;
                    if (ss.state() == NativeScanner.State.FOUND) {
                        anyUpcomingPresent = true;
                    }
                }
                files.add(ss.slot().file());
                if (ss.slot().bundled() && (Boolean.TRUE.equals(ss.matchesBundled()) || NativeBundle.isEmbedded(ss.slot().file()))) {
                    bundled = true;
                }
            }
            String fileLabel = String.join(", ", files);
            libraries.add(new LibInfo(
                g.title(),
                fileLabel,
                g.purpose(),
                gs.complete(),
                bundled,
                gs.anyInvalid(),
                isUpcomingGroup,
                anyUpcomingPresent
            ));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * 24.0)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void init() {
        refreshStatus();

        int w = Math.min(width - 16, 440);
        int x = (width - w) / 2;
        int bottom = height - 28;

        int btnW = (w - 18) / 4;

        // Button 1: Open natives folder
        addRenderableWidget(Button.builder(tr("bedray.natives.open_folder", "Open Folder"), b -> openNativesFolder())
            .bounds(x, bottom, btnW, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                tr("bedray.natives.open_folder.tip", "Open bedray/natives folder in your system file manager")
            ))
            .build());

        // Button 2: Refresh
        addRenderableWidget(Button.builder(tr("bedray.natives.refresh", "Refresh"), b -> {
            refreshStatus();
            status = tr("bedray.natives.refreshed", "Library statuses refreshed.");
            statusColor = 0xFFE0E0E0;
            rebuildWidgets();
        }).bounds(x + btnW + 6, bottom, btnW, 20).build());

        // Button 3: View Roadmap
        addRenderableWidget(Button.builder(tr("bedray.natives.roadmap", "Roadmap"), b -> {
            Minecraft.getInstance().gui.setScreen(new BedrayRoadmapScreen(this));
        }).bounds(x + (btnW + 6) * 2, bottom, btnW, 20).build());

        // Button 4: Done
        addRenderableWidget(Button.builder(tr("gui.done", "Done"), b -> onClose())
            .bounds(x + (btnW + 6) * 3, bottom, btnW, 20)
            .build());
    }

    private void openNativesFolder() {
        Path dir = resolveNativesDir();
        try {
            com.mojang.blaze3d.Blaze3D.openPath(dir);
        } catch (Throwable t) {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(dir.toFile());
                } else {
                    new ProcessBuilder("explorer.exe", dir.toString()).start();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        Path dir = resolveNativesDir();
        NativeInstaller.Report report = NativeInstaller.install(dir, files);

        if (report.count() > 0) {
            status = tr("bedray.natives.installed_count", "Installed %s library(s)! Restart Minecraft to apply.", report.count());
            statusColor = 0xFF55FF55;
            refreshStatus();
            rebuildWidgets();
        } else if (!report.errors().isEmpty()) {
            status = Component.literal(report.errors().get(0));
            statusColor = 0xFFFF7777;
        } else {
            status = tr("bedray.natives.none_found", "No recognized x64 DLLs found in dropped file(s).");
            statusColor = 0xFFFF7777;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);

        // Header Title
        g.text(font, title, (width - font.width(title)) / 2, 8, 0xFFFFFFFF);

        int w = Math.min(width - 16, 420);
        int x = (width - w) / 2;

        // Subtitle / Drag & drop hint
        Component hint = tr("bedray.natives.drag_hint", "Drop a .zip (e.g. bedray-natives.zip) or .dll into this window to install");
        int hintW = font.width(hint);
        int hintX = (width - hintW) / 2;
        g.text(font, hint, Math.max(x, hintX), 22, 0xFF80D0FF);

        // Library cards list
        int startY = 36;
        int listBottom = height - 48;
        int rowH = 26;
        int totalH = libraries.size() * rowH;
        maxScroll = Math.max(0, totalH - (listBottom - startY));

        for (int i = 0; i < libraries.size(); i++) {
            LibInfo lib = libraries.get(i);
            int ry = startY + i * rowH - scrollOffset;
            if (ry + rowH < startY || ry > listBottom) {
                continue;
            }

            // Name
            g.text(font, lib.name, x + 4, ry, 0xFFFFFFFF);

            // Status badge
            Component badgeText;
            int badgeColor;
            if (lib.invalid) {
                badgeText = tr("bedray.natives.invalid_badge", "[ INVALID ]");
                badgeColor = 0xFFFF5555;
            } else if (lib.upcoming) {
                if (lib.upcomingPresent) {
                    badgeText = tr("bedray.natives.ready_roadmap", "[ FILE READY / IN ROADMAP ]");
                    badgeColor = 0xFFFFFF55;
                } else {
                    badgeText = tr("bedray.natives.upcoming_badge", "[ UPCOMING / RESERVED ]");
                    badgeColor = 0xFF55FFFF;
                }
            } else if (lib.installed && lib.bundled) {
                badgeText = tr("bedray.natives.bundled_badge", "[ BUNDLED / READY ]");
                badgeColor = 0xFF55FFAA;
            } else if (lib.installed) {
                badgeText = tr("bedray.natives.installed_badge", "[ INSTALLED ]");
                badgeColor = 0xFF55FF55;
            } else {
                badgeText = tr("bedray.natives.missing_badge", "[ NOT FOUND ]");
                badgeColor = 0xFFAAAAAA;
            }
            int bw = font.width(badgeText);
            g.text(font, badgeText, x + w - bw - 4, ry, badgeColor);

            // Description / filename
            String sub = lib.fileName + "  *  " + lib.desc;
            g.text(font, font.plainSubstrByWidth(sub, w - 8), x + 4, ry + 11, lib.upcoming ? 0xFF88AA99 : 0xFF888888);
        }

        // Status message
        if (status != null && !status.getString().isEmpty()) {
            String msg = font.plainSubstrByWidth(status.getString(), w);
            int sw = font.width(msg);
            int sx = Math.max(x, (width - sw) / 2);
            g.text(font, msg, sx, height - 42, statusColor);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
