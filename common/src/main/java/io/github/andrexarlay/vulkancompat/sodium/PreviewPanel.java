package io.github.andrexarlay.vulkancompat.sodium;

import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Right-hand panel of the Bedray settings (Sodium Video Settings). Sodium 0.9.2 uses that area only for the tooltip of the hovered
 * option ({@code ScrollableTooltip}, area from the option list's right edge to the screen edge: decompiled
 * Sodium 0.9.2, VideoSettingsScreen.rebuild and ScrollableTooltip); {@code mixin.sodium.MixinScrollableTooltip} calls this panel.
 * <p>
 * Content (all from {@link SettingsCatalog}): name, value, the real in-game screenshots of the option with a before/after
 * comparison (Split slider that follows the mouse, or Flip between the two frames; choose what to compare with), a zoomed crop of
 * the effect, what changes, the price, requirements, and a "Reset to default" button. Screenshots are taken by the
 * {@code settings_preview} autotest (assets/bedray/textures/gui/preview/&lt;base&gt;_&lt;value&gt;.png, 640x360). When a screenshot does not
 * exist the panel says so in a box: nothing is drawn as a stand-in.
 * <p>
 * Layout is computed from the area Sodium gives (GUI units, so every GUI scale and window size): the panel never extends below
 * {@code reservedY} (the top of the Done / Apply / Undo buttons), content that does not fit scrolls with the mouse wheel, the picture
 * shrinks to the height that is left, and below {@link #MIN_PANEL_W} wide the panel is not drawn (Sodium's own tooltip overlay is used).
 */
public final class PreviewPanel {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/SodiumPage");
    private static final int IMG_W = 640;
    private static final int IMG_H = 360;
    private static final int CROP_W = 128;
    private static final int CROP_H = 72;
    public static final int MIN_PANEL_W = 100;
    private static final int MAX_PANEL_W = 400;

    private enum Mode { SPLIT, FLIP }

    private static Mode mode = Mode.SPLIT;
    private static boolean flipShowsAfter = true;
    private static boolean splitLocked;
    private static float lockedSplit = 0.5f;
    private static String compareKey;
    private static String comparePath = "";
    private static int scroll;
    private static int maxScroll;
    private static String lastLogged = "";
    private static Properties manifest;
    private static boolean modalOpen;
    private static boolean resetAllowed = true;
    private static Component runtimeStatus = Component.empty();
    private static int[] compactBtnRect = new int[4];

    /** Clickable rectangles of the last render: x, y, w, h, action id. */
    private record Hit(int x, int y, int w, int h, String action) {
    }

    private static final List<Hit> HITS = new ArrayList<>();
    private static int[] panelRect = new int[4];
    private static final Map<Identifier, Boolean> TEX_PRESENT = new HashMap<>();

    private PreviewPanel() {
    }

    public static boolean isModalOpen() {
        return modalOpen;
    }

    public static void closeModal() {
        modalOpen = false;
    }

    public static void openModal(Option option) { selected = option; modalOpen = true; scroll = 0; }
    public static void setResetAllowed(boolean allowed) { resetAllowed = allowed; }
    public static void setRuntimeStatus(Component status) { runtimeStatus = status; }

    /** Catalog entry of a Bedray option, or null (buttons and unknown options keep Sodium's tooltip). */
    public static SettingsCatalog.Info lookup(Identifier id, Option option) {
        if (id == null || !"bedray".equals(id.getNamespace())) {
            return null;
        }
        return SettingsCatalog.get(id.getPath());
    }

    private static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath("bedray", "textures/gui/preview/" + name + ".png");
    }

    private static boolean hasTexture(Identifier id) {
        return TEX_PRESENT.computeIfAbsent(id, k -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                return mc != null && mc.getResourceManager() != null && mc.getResourceManager().getResource(k).isPresent();
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    private static String note(String file) {
        if (manifest == null) {
            manifest = new Properties();
            try {
                var res = Minecraft.getInstance().getResourceManager().getResource(tex("manifest").withPath("textures/gui/preview/manifest.properties"));
                if (res.isPresent()) {
                    try (InputStream in = res.get().open()) {
                        manifest.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("no preview manifest: {}", e.toString());
            }
        }
        return manifest.getProperty(file, "");
    }

    /** Value key of the live option value for the previews of the entry. */
    static String currentKey(SettingsCatalog.Info info, Object v) {
        if (info.keyOf() != null) {
            return info.keyOf().apply(v);
        }
        if (v instanceof Boolean b) {
            return b ? "on" : "off";
        }
        if (v instanceof Enum<?> e) {
            return e.name().toLowerCase(Locale.ROOT);
        }
        if (v instanceof Number n) {
            String best = null;
            double bd = Double.MAX_VALUE;
            for (SettingsCatalog.Preview p : info.previews()) {
                try {
                    double d = Math.abs(Double.parseDouble(p.valueKey()) - n.doubleValue());
                    if (d < bd) {
                        bd = d;
                        best = p.valueKey();
                    }
                } catch (NumberFormatException ignored) {
                    // not a numeric key
                }
            }
            return best == null ? String.valueOf(v) : best;
        }
        return String.valueOf(v);
    }

    private static SettingsCatalog.Preview find(SettingsCatalog.Info info, String key) {
        for (SettingsCatalog.Preview p : info.previews()) {
            if (p.valueKey().equals(key)) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ render

    /** Returns false when the area is too small (the caller then lets Sodium draw its tooltip, plus a compact button to open the full modal). */
    public static boolean render(GuiGraphicsExtractor g, Font font, Dim2i area, int reservedLimitY, Option option,
                                 SettingsCatalog.Info info, int mouseX, int mouseY) {
        if (!comparePath.equals(info.path())) {
            comparePath = info.path();
            compareKey = null;
            scroll = 0;
            splitLocked = false;
            lockedSplit = 0.5f;
        }
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc != null && mc.getWindow() != null ? mc.getWindow().getGuiScaledWidth() : area.getLimitX();
        int screenH = mc != null && mc.getWindow() != null ? mc.getWindow().getGuiScaledHeight() : area.getLimitY();

        if (modalOpen) {
            int mw = Math.max(120, Math.min(screenW - 28, MAX_PANEL_W));
            int mx0 = (screenW - mw) / 2;
            int my0 = 14;
            int mLimitY = Math.min(screenH - 6, Math.max(my0 + 90, screenH - 14));
            g.nextStratum();
            g.fill(0, 0, screenW, screenH, 0xB806060A);
            drawPanelBox(g, font, mx0, my0, mw, mLimitY, option, info, mouseX, mouseY, true);
            compactBtnRect = new int[4];
            return true;
        }

        int x = area.x() + 6;
        int w = Math.min(area.width() - 9, MAX_PANEL_W);
        int y = area.y();
        int limitY = Math.min(area.getLimitY(), reservedLimitY);
        if (w < MIN_PANEL_W || limitY - y < 60) {
            panelRect = new int[4];
            HITS.clear();
            renderCompactButton(g, font, area, reservedLimitY, option, mouseX, mouseY);
            return false;
        }
        compactBtnRect = new int[4];
        drawPanelBox(g, font, x, y, w, limitY, option, info, mouseX, mouseY, false);
        return true;
    }

    private static void renderCompactButton(GuiGraphicsExtractor g, Font font, Dim2i area, int reservedLimitY, Option option, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc != null && mc.getWindow() != null ? mc.getWindow().getGuiScaledWidth() : area.getLimitX();
        Component btnText = Component.translatableWithFallback("bedray.preview.open_modal", "Info / Screenshots: %s", option.getName());
        int maxW = Math.min(240, screenW - 16);
        String label = font.plainSubstrByWidth(btnText.getString(), maxW - 12);
        int bw = Math.min(maxW, font.width(label) + 12);
        int bh = 16;
        int bx = Math.max(4, screenW - bw - 6);
        int by = Math.max(4, Math.min(area.y() - bh - 2, reservedLimitY - bh - 4));
        if (by < 4) {
            by = 4;
        }
        boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
        g.nextStratum();
        g.fill(bx, by, bx + bw, by + bh, hover ? 0xF03A5A9A : 0xE8223458);
        g.outline(bx, by, bw, bh, hover ? 0xFFFFFFFF : 0xFFA0C0FF);
        g.text(font, label, bx + (bw - font.width(label)) / 2, by + 4, 0xFFFFFFFF);
        compactBtnRect = new int[] {bx, by, bw, bh};
    }

    private static void drawPanelBox(GuiGraphicsExtractor g, Font font, int x, int y, int w, int limitY, Option option,
                                     SettingsCatalog.Info info, int mouseX, int mouseY, boolean isModal) {
        HITS.clear();
        panelRect = new int[] {x, y, w, limitY - y};
        String logKey = info.path() + "@" + w + "x" + (limitY - y);
        if (!logKey.equals(lastLogged)) {
            lastLogged = logKey;
            LOGGER.info("[VKB-AUTOTEST] ui_preview option={} images={} panel={}x{} at {},{} limit_y={}", info.path(),
                info.previews().size(), w, limitY - y, x, y, limitY);
        }
        g.nextStratum();
        g.fill(x, y, x + w, limitY, 0xF0101014);
        final int footer = 20;   // "Reset to default" (and Close in modal) stays visible, only the content above it scrolls
        int contentLimit = limitY - footer;
        g.enableScissor(x, y, x + w, contentLimit);
        g.outline(x, y, w, limitY - y, isModal ? 0xFFA0C0FF : 0x60FFFFFF);
        int cx = x + 4;
        int cw = w - 8 - 4;   // 4 px right for the scrollbar
        int top = y + 4 - scroll;
        int cy = top;

        // title (truncated: a long localized name must not overflow the narrow panel), value, impact
        g.text(font, font.plainSubstrByWidth(option.getName().copy().withStyle(ChatFormatting.BOLD).getString(), cw), cx, cy, 0xFFFFFFFF);
        cy += 11;
        Object value = option instanceof StatefulOption<?> so ? so.getValidatedValue() : null;
        if (value != null) {
            g.text(font, font.plainSubstrByWidth(Component.translatableWithFallback("bedray.preview.value", "Value: %s", valueText(option, value)).getString(), cw),
                cx, cy, 0xFFAAAAAA);
            cy += 11;
        }
        if (option.getImpact() != null) {
            g.text(font, font.plainSubstrByWidth(Component.translatable("sodium.options.performance_impact_string", option.getImpact().getName()).getString(), cw),
                cx, cy, 0xFFCCCCCC);
            cy += 11;
        }
        cy += 2;
        if (!runtimeStatus.getString().isEmpty()) cy = paragraph(g, font, runtimeStatus, cx, cy, cw, 0xFF8ABCD8) + 3;

        // picture block (drawn only when actual preview screenshots exist — never show a placeholder box)
        if (info.hasPreviews()) {
            int availForPicture = Math.max(40, (limitY - y) * 55 / 100);
            cy = pictureBlock(g, font, info, value, cx, cy, cw, availForPicture, mouseX, mouseY, x, y, x + w, contentLimit);
        }

        // explanation, price, requirement, scene
        cy = paragraph(g, font, Component.translatableWithFallback("bedray.opt." + info.path() + ".desc", info.desc()), cx, cy, cw, 0xFFFFFFFF);
        if (!info.requires().isEmpty()) {
            cy = paragraph(g, font, Component.translatableWithFallback("bedray.opt." + info.path() + ".req", info.requires()), cx, cy + 2, cw, 0xFFFFA060);
        }
        cy += 4;
        int contentH = cy - top + 4;
        maxScroll = Math.max(0, contentH - (contentLimit - y));
        scroll = Mth.clamp(scroll, 0, maxScroll);
        if (maxScroll > 0) {
            int trackH = contentLimit - y - 4;
            int thumbH = Math.max(12, trackH * (contentLimit - y) / contentH);
            int thumbY = y + 2 + (trackH - thumbH) * scroll / maxScroll;
            g.fill(x + w - 4, thumbY, x + w - 1, thumbY + thumbH, 0xA0FFFFFF);
        }
        g.disableScissor();
        g.fill(x + 1, contentLimit, x + w - 1, contentLimit + 1, 0x40FFFFFF);
        Component label = Component.translatableWithFallback("bedray.preview.reset", "Reset to default");
        int bw = Math.min(w - 8, font.width(label) + 12);
        if (resetAllowed) button(g, font, x + 4, contentLimit + 4, bw, 14, label, mouseX, mouseY, "reset");
        if (isModal) {
            Component closeLbl = Component.translatableWithFallback("bedray.preview.close", "Close [Esc]");
            int cbw = Math.min(w - bw - 12, font.width(closeLbl) + 12);
            if (cbw > 24) {
                button(g, font, x + w - cbw - 4, contentLimit + 4, cbw, 14, closeLbl, mouseX, mouseY, "close_modal");
            }
        }
    }

    private static String valueText(Option option, Object v) {
        if (v instanceof Boolean b) {
            return b ? "on" : "off";
        }
        if (v instanceof Enum<?> e) {
            return e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return String.valueOf(v);
    }

    private static int pictureBlock(GuiGraphicsExtractor g, Font font, SettingsCatalog.Info info, Object value, int cx, int cy, int cw,
                                    int maxH, int mouseX, int mouseY, int scX0, int scY0, int scX1, int scY1) {
        String cur = value == null ? info.previews().get(0).valueKey() : currentKey(info, value);
        SettingsCatalog.Preview after = find(info, cur);
        if (after == null) {
            after = info.previews().get(0);
            cur = after.valueKey();
        }
        if (compareKey == null || compareKey.equals(cur) || find(info, compareKey) == null) {
            compareKey = null;
            for (SettingsCatalog.Preview p : info.previews()) {
                if (!p.valueKey().equals(cur)) {
                    compareKey = p.valueKey();
                    break;
                }
            }
        }
        SettingsCatalog.Preview before = compareKey == null ? null : find(info, compareKey);

        Identifier aTex = tex(info.previewBase() + "_" + after.valueKey());
        boolean aHas = hasTexture(aTex);
        Identifier bTex = before == null ? null : tex(info.previewBase() + "_" + before.valueKey());
        boolean bHas = bTex != null && hasTexture(bTex);

        // If the primary screenshot does not exist, skip the image block completely (no placeholder box).
        if (!aHas) {
            return cy;
        }

        // mode switch
        int bx = cx;
        if (before != null && bHas) {
            bx += chip(g, font, bx, cy, Component.translatableWithFallback("bedray.preview.mode_split", "Slider"), mode == Mode.SPLIT, mouseX, mouseY, "mode_split") + 3;
            bx += chip(g, font, bx, cy, Component.translatableWithFallback("bedray.preview.mode_flip", "Flip"), mode == Mode.FLIP, mouseX, mouseY, "mode_flip") + 3;
            String hint = mode == Mode.SPLIT
                ? (splitLocked ? Component.translatableWithFallback("bedray.preview.locked", "click picture to unlock").getString()
                    : Component.translatableWithFallback("bedray.preview.hint_split", "mouse moves the split, click locks").getString())
                : Component.translatableWithFallback("bedray.preview.hint_flip", "click the picture to flip").getString();
            int room = cx + cw - (bx + 2);
            if (room > 30) {
                g.text(font, font.plainSubstrByWidth(hint, room), bx + 2, cy + 3, 0xFF909090);
            }
            cy += 15;
        }

        // picture size: 16:9, as wide as fits the height that is left
        int ih = Math.min(cw * IMG_H / IMG_W, Math.max(36, maxH));
        int iw = ih * IMG_W / IMG_H;
        int ix = cx + (cw - iw) / 2;
        int iy = cy;

        if (before == null || !bHas) {
            blit(g, aTex, ix, iy, iw, ih, 0, 0, IMG_W, IMG_H);
            label(g, font, after.label(), ix + 2, iy + 2);
        } else if (mode == Mode.FLIP) {
            Identifier t = flipShowsAfter ? aTex : bTex;
            blit(g, t, ix, iy, iw, ih, 0, 0, IMG_W, IMG_H);
            label(g, font, (flipShowsAfter ? after : before).label(), ix + 2, iy + 2);
            HITS.add(new Hit(ix, iy, iw, ih, "flip"));
        } else {
            float split;
            if (!splitLocked && mouseX >= ix && mouseX < ix + iw && mouseY >= iy && mouseY < iy + ih) {
                split = (mouseX - ix) / (float) iw;
                lockedSplit = Mth.clamp(split, 0.05f, 0.95f);
            } else {
                split = Mth.clamp(lockedSplit, 0.05f, 0.95f);
            }
            int sx = Math.round(iw * split);
            // Draw full "after" image first, then hardware-scissor the left [ix, ix+sx] region for "before"
            // so both halves use identical full-frame UV projection (zero sub-pixel swimming/stretching!).
            blit(g, aTex, ix, iy, iw, ih, 0, 0, IMG_W, IMG_H);
            int clipX0 = Math.max(scX0, ix);
            int clipY0 = Math.max(scY0, iy);
            int clipX1 = Math.min(scX1, ix + sx);
            int clipY1 = Math.min(scY1, iy + ih);
            if (clipX1 > clipX0 && clipY1 > clipY0) {
                g.disableScissor();
                g.enableScissor(clipX0, clipY0, clipX1, clipY1);
                blit(g, bTex, ix, iy, iw, ih, 0, 0, IMG_W, IMG_H);
                g.disableScissor();
                g.enableScissor(scX0, scY0, scX1, scY1);
            }
            g.fill(ix + sx - 1, iy, ix + sx + 1, iy + ih, 0xFFFFFFFF);
            label(g, font, before.label(), ix + 2, iy + 2);
            labelRight(g, font, after.label(), ix + iw - 2, iy + 2);
            HITS.add(new Hit(ix, iy, iw, ih, "split"));
        }
        g.outline(ix, iy, iw, ih, 0x60FFFFFF);
        cy = iy + ih + 3;

        // compare-with chips
        if (info.previews().size() > 1 && bHas) {
            g.text(font, Component.translatableWithFallback("bedray.preview.compare_with", "Compare with:"), cx, cy + 3, 0xFFAAAAAA);
            int cxx = cx + font.width(Component.translatableWithFallback("bedray.preview.compare_with", "Compare with:")) + 4;
            int row = cy;
            for (SettingsCatalog.Preview p : info.previews()) {
                if (p.valueKey().equals(cur)) {
                    continue;
                }
                int cwid = font.width(p.label()) + 8;
                if (cxx + cwid > cx + cw) {
                    cxx = cx;
                    row += 15;
                }
                chip(g, font, cxx, row, Component.literal(p.label()), p.valueKey().equals(compareKey), mouseX, mouseY, "cmp:" + p.valueKey());
                cxx += cwid + 3;
            }
            cy = row + 16;
        }

        // zoomed crop of the effect (both frames, same region) when there is room
        if (aHas && before != null && bHas) {
            int boxW = (cw - 4) / 2;
            int boxH = boxW * CROP_H / CROP_W;
            int u = Mth.clamp(Math.round(info.cropU() * IMG_W) - CROP_W / 2, 0, IMG_W - CROP_W);
            int v = Mth.clamp(Math.round(info.cropV() * IMG_H) - CROP_H / 2, 0, IMG_H - CROP_H);
            blit(g, bTex, cx, cy, boxW, boxH, u, v, CROP_W, CROP_H);
            g.outline(cx, cy, boxW, boxH, 0x80FFFFFF);
            float zoom = (boxW / (float) CROP_W) / (iw / (float) IMG_W);
            label(g, font, before.label() + String.format(Locale.ROOT, " x%.1f", zoom), cx + 2, cy + 2);
            blit(g, aTex, cx + boxW + 4, cy, boxW, boxH, u, v, CROP_W, CROP_H);
            g.outline(cx + boxW + 4, cy, boxW, boxH, 0x80FFFFFF);
            label(g, font, after.label(), cx + boxW + 6, cy + 2);
            cy += boxH + 3;
        }
        String capt = note(info.previewBase() + "_" + after.valueKey());
        String scene = Component.translatableWithFallback("bedray.preview.scene." + info.scene().name().toLowerCase(Locale.ROOT), sceneName(info.scene())).getString();
        g.text(font, font.plainSubstrByWidth(scene + (capt.isEmpty() ? "" : " | " + capt), cw), cx, cy, 0xFF808080);
        return cy + 14;
    }

    private static String sceneName(SettingsCatalog.Scene s) {
        return switch (s) {
            case DAY -> "Scene: desert, day";
            case SUNSET -> "Scene: sunset";
            case FOREST -> "Scene: forest";
            case WATER -> "Scene: water";
            case INTERIOR -> "Scene: room, one lamp";
            case LAVA -> "Scene: lava, night";
        };
    }

    private static int paragraph(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int w, int color) {
        for (FormattedCharSequence line : font.split(text, w)) {
            g.text(font, line, x, y, color);
            y += 10;
        }
        return y;
    }

    private static void label(GuiGraphicsExtractor g, Font font, String s, int x, int y) {
        int tw = font.width(s);
        g.fill(x - 1, y - 1, x + tw + 1, y + 9, 0xA0000000);
        g.text(font, s, x, y, 0xFFFFFFFF);
    }

    private static void labelRight(GuiGraphicsExtractor g, Font font, String s, int right, int y) {
        label(g, font, s, right - font.width(s), y);
    }

    private static int chip(GuiGraphicsExtractor g, Font font, int x, int y, Component text, boolean active, int mx, int my, String action) {
        int w = font.width(text) + 8;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 13;
        g.fill(x, y, x + w, y + 13, active ? 0xFF3A5A9A : hover ? 0xFF404048 : 0xFF2A2A30);
        g.outline(x, y, w, 13, active ? 0xFFA0C0FF : 0x60FFFFFF);
        g.text(font, text, x + 4, y + 3, 0xFFFFFFFF);
        HITS.add(new Hit(x, y, w, 13, action));
        return w;
    }

    private static void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, Component text, int mx, int my, String action) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, hover ? 0xFF505058 : 0xFF383840);
        g.outline(x, y, w, h, 0x80FFFFFF);
        g.text(font, text, x + (w - font.width(text)) / 2, y + (h - 8) / 2, 0xFFFFFFFF);
        HITS.add(new Hit(x, y, w, h, action));
    }

    private static void blit(GuiGraphicsExtractor g, Identifier id, int x, int y, int w, int h, int u, int v, int uw, int vh) {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, (float) u, (float) v, w, h, uw, vh, IMG_W, IMG_H);
    }

    // ------------------------------------------------------------------ input

    /** True when the point is inside the last drawn panel or compact button. */
    public static boolean contains(double mx, double my) {
        if (modalOpen) {
            return true;
        }
        if (compactBtnRect[2] > 0 && mx >= compactBtnRect[0] && mx < compactBtnRect[0] + compactBtnRect[2]
            && my >= compactBtnRect[1] && my < compactBtnRect[1] + compactBtnRect[3]) {
            return true;
        }
        return panelRect[2] > 0 && mx >= panelRect[0] && mx < panelRect[0] + panelRect[2] && my >= panelRect[1] && my < panelRect[1] + panelRect[3];
    }

    /** Mouse click (left button) in GUI coordinates; returns true when the panel or compact button used it. */
    public static boolean click(double mx, double my) {
        if (selected == null) {
            return false;
        }
        if (!modalOpen && compactBtnRect[2] > 0
            && mx >= compactBtnRect[0] && mx < compactBtnRect[0] + compactBtnRect[2]
            && my >= compactBtnRect[1] && my < compactBtnRect[1] + compactBtnRect[3]) {
            modalOpen = true;
            scroll = 0;
            return true;
        }
        boolean inPanel = panelRect[2] > 0 && mx >= panelRect[0] && mx < panelRect[0] + panelRect[2]
            && my >= panelRect[1] && my < panelRect[1] + panelRect[3];
        if (modalOpen && !inPanel) {
            modalOpen = false;
            return true;
        }
        if (!inPanel) {
            return false;
        }
        for (Hit h : HITS) {
            if (mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                switch (h.action) {
                    case "mode_split" -> mode = Mode.SPLIT;
                    case "mode_flip" -> mode = Mode.FLIP;
                    case "flip" -> flipShowsAfter = !flipShowsAfter;
                    case "split" -> splitLocked = !splitLocked;
                    case "close_modal" -> modalOpen = false;
                    case "reset" -> {
                        if (!resetAllowed) return true;
                        selected.resetToDefault();
                        LOGGER.info("[VKB-AUTOTEST] ui_option_reset option={}", comparePath);
                    }
                    default -> {
                        if (h.action.startsWith("cmp:")) {
                            compareKey = h.action.substring(4);
                        }
                    }
                }
                return true;
            }
        }
        return true;   // swallow clicks on the panel body
    }

    /** Mouse wheel over the panel scrolls its content. */
    public static boolean scrolled(double mx, double my, double amount) {
        boolean inPanel = panelRect[2] > 0 && mx >= panelRect[0] && mx < panelRect[0] + panelRect[2]
            && my >= panelRect[1] && my < panelRect[1] + panelRect[3];
        if ((!inPanel && !modalOpen) || maxScroll <= 0) {
            return false;
        }
        scroll = Mth.clamp(scroll - (int) (amount * 12), 0, maxScroll);
        return true;
    }

    /** For the autotest: action names of the last rendered clickable areas with their centres. */
    public static List<String> hitNames() {
        List<String> out = new ArrayList<>();
        for (Hit h : HITS) {
            out.add(h.action + "@" + (h.x + h.w / 2) + "," + (h.y + h.h / 2));
        }
        return out;
    }

    public static int[] lastPanelRect() {
        return panelRect.clone();
    }

    // ------------------------------------------------------------------ selection

    /** Remembered "selected" option of our pages (shown while nothing else is hovered). Reset when the screen closes. */
    private static Option selected;

    public static Option selected() {
        return selected;
    }

    public static void select(Option o) {
        selected = o;
    }

    /** Called once per screen: the stored option belongs to the old screen's config state. */
    public static void clearSelection() {
        selected = null;
        modalOpen = false;
        panelRect = new int[4];
        compactBtnRect = new int[4];
        HITS.clear();
        TEX_PRESENT.clear();
        manifest = null;
        runtimeStatus = Component.empty();
    }

    /** Minecraft cursor in GUI coordinates (MouseHandler.getScaledXPos/YPos(Window), javap). */
    public static int mouseX() {
        Minecraft mc = Minecraft.getInstance();
        return (int) mc.mouseHandler.getScaledXPos(mc.getWindow());
    }

    public static int mouseY() {
        Minecraft mc = Minecraft.getInstance();
        return (int) mc.mouseHandler.getScaledYPos(mc.getWindow());
    }
}
