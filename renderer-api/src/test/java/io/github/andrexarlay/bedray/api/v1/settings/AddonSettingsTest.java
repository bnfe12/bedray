package io.github.andrexarlay.bedray.api.v1.settings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.andrexarlay.bedray.api.v1.settings.AddonSettings.*;
import static org.junit.jupiter.api.Assertions.*;

class AddonSettingsTest {
    private static final Text TITLE = Text.literal("Sample Add-on");
    private static final Text CATEGORY = Text.literal("Lighting");
    private static final Text HELP = Text.literal("Controls the sample renderer");

    @Test
    void readsOneSnapshotAndUsesDefaultsForRemovedOrInvalidPersistedValues() {
        TestStorage storage = new TestStorage();
        storage.values.putAll(Map.of("enabled", false, "bounces", 7, "quality", "removed", "old_key", 42));
        Registration registration = sample("test_read", storage);

        Map<String, Object> snapshot = registration.read();

        assertEquals(Map.of("enabled", false, "bounces", 4, "quality", "balanced"), snapshot);
        assertEquals(1, storage.reads);
        assertFalse(registration.options().get(1).enabled(snapshot));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("enabled", true));

        Map<String, Object> draft = new LinkedHashMap<>(snapshot);
        draft.put("enabled", true);
        assertTrue(registration.options().get(1).enabled(draft));
        assertEquals(false, storage.values.get("enabled"), "staged changes do not mutate the owner");
    }

    @Test
    void validatesAllChangesBeforeCallingTheOwnerOnceWithAnImmutablePatch() {
        TestStorage storage = new TestStorage();
        Registration registration = sample("test_apply", storage);
        assertThrows(IllegalArgumentException.class, () -> registration.apply(Map.of("enabled", false, "bounces", 3)));
        assertThrows(IllegalArgumentException.class, () -> registration.apply(Map.of("wrong_key", "fast")));
        assertThrows(IllegalArgumentException.class, () -> registration.apply(Map.of("bounces", 2L)));
        registration.apply(Map.of());
        assertEquals(0, storage.applies);

        Map<String, Object> patch = new LinkedHashMap<>(Map.of("enabled", false, "bounces", 6, "quality", "fast"));
        registration.apply(patch);
        patch.put("enabled", true);

        assertEquals(1, storage.applies);
        assertEquals(Map.of("enabled", false, "bounces", 6, "quality", "fast"), storage.values);
        assertEquals(false, storage.lastPatch.get("enabled"));
        assertThrows(UnsupportedOperationException.class, () -> storage.lastPatch.put("enabled", true));
    }

    @Test
    void rejectsInvalidDescriptorsAndDependenciesAtRegistrationConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new IntegerOption(details("count"), 1, 0, 8, 2));
        assertThrows(IllegalArgumentException.class, () -> new IntegerOption(details("count"), 4, 0, 7, 2));
        assertThrows(IllegalArgumentException.class, () -> new ChoiceOption(details("quality"), "removed",
            List.of(new Choice("fast", Text.literal("Fast")))));
        assertThrows(IllegalArgumentException.class, () -> registration(List.of(toggle("a"), toggle("a"))));
        assertThrows(IllegalArgumentException.class, () -> registration(List.of(toggle("a", new Condition("missing", true)))));
        assertThrows(IllegalArgumentException.class, () -> registration(List.of(
            toggle("a", new Condition("b", "fast")), toggle("b"))));
        assertThrows(IllegalArgumentException.class, () -> registration(List.of(
            toggle("a", new Condition("b", true)), toggle("b", new Condition("a", true)))));
    }

    @Test
    void registryRequiresUniqueNamespacesAndTakesImmutableSnapshotsWithoutCallingOwners() {
        TestStorage storage = new TestStorage();
        List<Option<?>> options = new ArrayList<>(List.of(toggle("enabled")));
        Registration registration = new Registration("addon_test_registry", TITLE, options, storage);
        options.clear();
        List<Registration> before = registrations();

        register(registration);

        assertFalse(before.contains(registration));
        assertTrue(registrations().contains(registration));
        assertEquals(1, registration.options().size());
        assertThrows(UnsupportedOperationException.class, () -> registration.options().clear());
        assertThrows(UnsupportedOperationException.class, () -> registrations().clear());
        assertThrows(IllegalArgumentException.class, () -> register(registration));
        assertThrows(IllegalArgumentException.class, () -> sample("bedray", storage));
        assertThrows(IllegalArgumentException.class, () -> sample("../outside", storage));
        assertThrows(IllegalArgumentException.class, () -> details("foreign:enabled"));
        assertEquals(0, storage.reads);
        assertEquals(0, storage.applies);
        registration.read();
        registration.apply(Map.of("enabled", false));
    }

    private static Registration sample(String namespace, TestStorage storage) {
        return new Registration(namespace, TITLE, List.of(
            toggle("enabled"),
            new IntegerOption(details("bounces", new Condition("enabled", true)), 4, 2, 8, 2),
            new ChoiceOption(details("quality"), "balanced", List.of(
                new Choice("fast", Text.literal("Fast")), new Choice("balanced", Text.literal("Balanced"))))
        ), storage);
    }

    private static Registration registration(List<Option<?>> options) {
        return new Registration("test_validation", TITLE, options, new TestStorage());
    }

    private static BooleanOption toggle(String id, Condition... requires) {
        return new BooleanOption(details(id, requires), true);
    }

    private static Details details(String id, Condition... requires) {
        return new Details(id, CATEGORY, 0, Text.literal(id), HELP, List.of(requires));
    }

    private static final class TestStorage implements Storage {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private Map<String, Object> lastPatch;
        private int reads;
        private int applies;

        @Override public Map<String, ?> read() {
            assertFalse(Thread.holdsLock(AddonSettings.class));
            reads++;
            return Map.copyOf(values);
        }

        @Override public void apply(Map<String, Object> changes) {
            assertFalse(Thread.holdsLock(AddonSettings.class));
            applies++;
            lastPatch = changes;
            values.putAll(changes);
        }
    }
}
