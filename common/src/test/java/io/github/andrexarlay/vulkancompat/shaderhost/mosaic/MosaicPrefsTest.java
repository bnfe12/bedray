package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicKeymap.Action;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicKeymap.Combo;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Shortcuts and editor settings: defaults, rebinding, text form and the settings file. */
class MosaicPrefsTest {

    @Test
    void defaultsHaveNoDuplicateCombosAndEveryActionIsReachable() {
        MosaicKeymap k = new MosaicKeymap();
        Set<Combo> seen = new HashSet<>();
        for (Action a : Action.values()) {
            assertFalse(k.get(a).isEmpty(), a + " has no default shortcut");
            for (Combo c : k.get(a)) {
                assertTrue(seen.add(c), c + " bound twice");
                assertEquals(a, k.match(c.key(), c.mods()));
            }
        }
        assertEquals(Action.REDO, k.match('Z', MosaicKeymap.CTRL | MosaicKeymap.SHIFT));
        assertEquals(Action.UNDO, k.match('Z', MosaicKeymap.CTRL));
        assertNull(k.match('Z', 0));
    }

    @Test
    void comboTextRoundTrips() {
        for (Action a : Action.values())
            for (Combo c : new MosaicKeymap().get(a)) assertEquals(c, MosaicKeymap.parse(MosaicKeymap.format(c)), c.toString());
        assertEquals(new Combo('Z', MosaicKeymap.CTRL | MosaicKeymap.SHIFT), MosaicKeymap.parse("shift+ctrl+z"));
        assertEquals(new Combo(290, 0), MosaicKeymap.parse("F1"));
        assertNull(MosaicKeymap.parse("Ctrl+"));
        assertNull(MosaicKeymap.parse("Hyper+Q+"));
    }

    @Test
    void bindingTakesTheComboFromItsPreviousAction() {
        MosaicKeymap k = new MosaicKeymap();
        Combo ctrlD = new Combo('D', MosaicKeymap.CTRL);
        Action stolen = k.bind(Action.DELETE, 0, ctrlD);
        assertEquals(Action.DUPLICATE, stolen);
        assertEquals(Action.DELETE, k.match('D', MosaicKeymap.CTRL));
        assertTrue(k.get(Action.DUPLICATE).isEmpty());
        assertEquals(2, k.get(Action.DELETE).size()); // slot 0 replaced, Backspace kept
        k.unbind(Action.DELETE, 1);
        assertEquals(List.of(ctrlD), k.get(Action.DELETE));
    }

    @Test
    void prefsSurviveTheFileAndToleranceKeepsGoodFields() {
        MosaicPrefs p = new MosaicPrefs();
        p.snap = true;
        p.grid = 24;
        p.wireThickness = 3;
        p.toggleFavorite("mosaic.mix");
        p.used("mosaic.add");
        p.used("mosaic.mul");
        p.used("mosaic.add");
        p.collapsed.add("MATH");
        p.keys.bind(Action.HELP, 0, new Combo('H', 0));
        MosaicPrefs q = MosaicPrefs.fromJson(p.toJson());
        assertTrue(q.snap);
        assertEquals(24, q.grid);
        assertEquals(3, q.wireThickness);
        assertEquals(List.of("mosaic.mix"), q.favorites);
        assertEquals(List.of("mosaic.add", "mosaic.mul"), q.recent);
        assertTrue(q.collapsed.contains("MATH"));
        assertEquals(Action.HELP, q.keys.match('H', 0));
        assertNull(q.keys.match(290, 0));

        MosaicPrefs broken = MosaicPrefs.fromJson("{\"grid\": \"wide\", \"snap\": true, \"favorites\": [\"mosaic.nope\", 3],"
            + " \"keys\": {\"undo\": [\"Ctrl+Q\", \"???\"]}}");
        assertTrue(broken.snap);
        assertEquals(16, broken.grid);
        assertTrue(broken.favorites.isEmpty());
        assertEquals(List.of(new Combo('Q', MosaicKeymap.CTRL)), broken.keys.get(Action.UNDO));
        assertEquals(Action.REDO, broken.keys.match('Y', MosaicKeymap.CTRL)); // untouched actions keep defaults
        assertEquals(16, MosaicPrefs.fromJson("not json").grid);
    }

    @Test
    void recentListIsCappedAndSnappingRounds() {
        MosaicPrefs p = new MosaicPrefs();
        for (String t : MosaicGraph.knownTypes()) p.used(t);
        assertEquals(MosaicPrefs.RECENT_MAX, p.recent.size());
        p.grid = 16;
        assertEquals(32, p.snapped(25));
        assertEquals(-16, p.snapped(-9));
    }
}
