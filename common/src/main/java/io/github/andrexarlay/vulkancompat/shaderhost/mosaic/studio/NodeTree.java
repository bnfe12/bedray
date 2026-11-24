package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.StudioUi.*;

/**
 * The categorized node list shared by the palette and the add menu: collapsible groups with icons and counts
 * (click, Enter or Left/Right toggle a group; the state is remembered), a filter that opens every match, and items
 * that are picked with Enter / double-click or dragged onto a canvas.
 */
final class NodeTree extends JList<Object> {

    static final String DRAG_PREFIX = "mosaic-item:";

    record Group(String name, Color color, int count, boolean open) {}

    private final DefaultListModel<Object> rows = new DefaultListModel<>();
    private final java.util.function.Supplier<List<StudioModel.Item>> source;
    private String filter = "";
    private Consumer<StudioModel.Item> onPick = it -> { };

    NodeTree(java.util.function.Supplier<List<StudioModel.Item>> source, boolean draggable) {
        this.source = source;
        setModel(rows);
        setBackground(PANEL);
        setForeground(TEXT);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setCellRenderer(new Renderer());
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int i = locationToIndex(e.getPoint());
                if (i < 0 || !getCellBounds(i, i).contains(e.getPoint())) return;
                Object v = rows.get(i);
                if (v instanceof Group g) toggle(g.name());
                else if (v instanceof StudioModel.Item it && e.getClickCount() == 2) onPick.accept(it);
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                Object v = getSelectedValue();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_ENTER -> {
                        if (v instanceof Group g) toggle(g.name());
                        else if (v instanceof StudioModel.Item it) onPick.accept(it);
                        e.consume();
                    }
                    case KeyEvent.VK_RIGHT -> {
                        if (v instanceof Group g && !g.open()) toggle(g.name());
                        e.consume();
                    }
                    case KeyEvent.VK_LEFT -> {
                        String group = v instanceof Group g ? g.name() : v instanceof StudioModel.Item it ? it.category() : null;
                        if (group != null && !settings.collapsed.contains(group)) toggle(group);
                        e.consume();
                    }
                    default -> { }
                }
            }
        });
        if (draggable) {
            setDragEnabled(true);
            setTransferHandler(new TransferHandler() {
                @Override public int getSourceActions(JComponent c) { return COPY; }
                @Override protected Transferable createTransferable(JComponent c) {
                    return getSelectedValue() instanceof StudioModel.Item it ? new StringSelection(DRAG_PREFIX + it.type()) : null;
                }
            });
        }
        rebuild();
    }

    void onPick(Consumer<StudioModel.Item> c) { onPick = c; }

    void setFilter(String f) {
        filter = f == null ? "" : f.trim().toLowerCase(Locale.ROOT);
        rebuild();
        selectFirstItem();
    }

    void selectFirstItem() {
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i) instanceof StudioModel.Item) {
                setSelectedIndex(i);
                ensureIndexIsVisible(i);
                return;
            }
        if (!rows.isEmpty()) setSelectedIndex(0);
    }

    /** The selected item, or the first match when a group header is selected. */
    StudioModel.Item current() {
        Object v = getSelectedValue();
        if (v instanceof StudioModel.Item it) return it;
        for (int i = Math.max(0, getSelectedIndex()); i < rows.size(); i++) if (rows.get(i) instanceof StudioModel.Item it) return it;
        return null;
    }

    void moveSelection(int d) {
        int i = getSelectedIndex() + d;
        if (i >= 0 && i < rows.size()) {
            setSelectedIndex(i);
            ensureIndexIsVisible(i);
        }
    }

    void toggle(String group) {
        if (!filter.isEmpty()) return; // while filtering every match is open
        if (!settings.collapsed.remove(group)) settings.collapsed.add(group);
        settings.save();
        Object keep = getSelectedValue();
        rebuild();
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i) instanceof Group g && g.name().equals(group)) {
                setSelectedIndex(i);
                ensureIndexIsVisible(i);
                return;
            }
        if (keep != null) setSelectedValue(keep, true);
    }

    void rebuild() {
        Map<String, List<StudioModel.Item>> groups = new LinkedHashMap<>();
        for (StudioModel.Item it : source.get()) {
            if (settings.hiddenLibraries.contains(it.category())) continue;
            if (!filter.isEmpty() && !(it.label().toLowerCase(Locale.ROOT).contains(filter) || it.type().toLowerCase(Locale.ROOT).contains(filter)
                || it.help().toLowerCase(Locale.ROOT).contains(filter) || it.category().toLowerCase(Locale.ROOT).startsWith(filter))) continue;
            groups.computeIfAbsent(it.category(), c -> new ArrayList<>()).add(it);
        }
        rows.clear();
        for (var e : groups.entrySet()) {
            boolean open = !filter.isEmpty() || !settings.collapsed.contains(e.getKey());
            rows.addElement(new Group(e.getKey(), e.getValue().get(0).color(), e.getValue().size(), open));
            if (open) e.getValue().forEach(rows::addElement);
        }
    }

    private final class Renderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
            JLabel c = (JLabel) super.getListCellRendererComponent(l, "", i, sel, false);
            c.setBackground(sel ? SELECT.darker().darker() : PANEL);
            c.setOpaque(true);
            if (v instanceof Group g) {
                c.setText(g.name() + "   " + g.count());
                c.setFont(UI_B.deriveFont(UI_S.getSize2D()));
                c.setForeground(TEXT);
                c.setIcon(new Icon() {
                    public int getIconWidth() { return 34; }
                    public int getIconHeight() { return 16; }
                    public void paintIcon(Component comp, Graphics g0, int x, int y) {
                        Graphics2D g2 = (Graphics2D) g0.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(MUTED);
                        int cx = x + 5, cy = y + 8;
                        if (g.open()) g2.fillPolygon(new int[] {cx - 4, cx + 4, cx}, new int[] {cy - 2, cy - 2, cy + 3}, 3);
                        else g2.fillPolygon(new int[] {cx - 2, cx - 2, cx + 3}, new int[] {cy - 4, cy + 4, cy}, 3);
                        g2.translate(x + 15, y);
                        Icons.draw(g2, g.name(), g.color(), 16);
                        g2.dispose();
                    }
                });
                c.setBorder(new EmptyBorder(6, 4, 4, 4));
                c.setToolTipText(g.open() ? "Click or Left: collapse" : "Click or Right: expand");
            } else if (v instanceof StudioModel.Item it) {
                c.setText(it.label());
                c.setFont(UI);
                c.setForeground(TEXT);
                c.setIcon(Icons.category(it.category(), it.color(), 12));
                c.setIconTextGap(8);
                c.setBorder(new EmptyBorder(2, 26, 2, 4));
                c.setToolTipText("<html><b>" + esc(it.label()) + "</b><br>" + esc(it.help()) + "<br><i>Drag onto the graph, or double-click / Enter</i></html>");
            }
            return c;
        }
    }
}
