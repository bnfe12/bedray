package io.github.andrexarlay.bedray.api.v1.settings;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Client settings contributed by add-ons to the Bedray settings screen. */
public final class AddonSettings {
    private static final Pattern ID = Pattern.compile("[a-z0-9_][a-z0-9_.-]{0,63}");
    private static final Set<String> RESERVED_NAMESPACES = Set.of("bedray", "minecraft", "sodium");
    private static final Map<String, Registration> REGISTRATIONS = new LinkedHashMap<>();

    private AddonSettings() {}

    /** Register once during client initialization. A namespace identifies one owner for the life of the client. */
    public static synchronized void register(Registration registration) {
        Objects.requireNonNull(registration, "registration");
        if (REGISTRATIONS.putIfAbsent(registration.namespace(), registration) != null) {
            throw new IllegalArgumentException("Add-on settings are already registered for " + registration.namespace());
        }
    }

    /** Returns a registration snapshot. Reading it does not invoke add-on code. */
    public static synchronized List<Registration> registrations() {
        return List.copyOf(REGISTRATIONS.values());
    }

    /** Text from the add-on's language file with a fallback. An empty key displays the fallback directly. */
    public record Text(String key, String fallback) {
        public Text {
            Objects.requireNonNull(key, "translation key");
            Objects.requireNonNull(fallback, "fallback");
            if (!key.isEmpty() && key.isBlank()) throw new IllegalArgumentException("Blank translation key");
        }

        public static Text literal(String text) { return new Text("", text); }
    }

    /** A control is available when this same-owner option has the specified value. */
    public record Condition(String optionId, Object value) {
        public Condition {
            requireId(optionId, "condition option");
            if (!(value instanceof Boolean || value instanceof Integer || value instanceof String)) {
                throw new IllegalArgumentException("A condition value must be a boolean, integer or choice ID");
            }
        }
    }

    /** The owner namespace and this local ID together identify a setting. Lower order values appear first. */
    public record Details(String id, Text category, int order, Text name, Text help, List<Condition> requires) {
        public Details {
            requireId(id, "option");
            requireLabel(category, "category");
            requireLabel(name, "option name");
            Objects.requireNonNull(help, "option help");
            requires = List.copyOf(requires);
        }

        public Details(String id, Text category, int order, Text name, Text help) {
            this(id, category, order, name, help, List.of());
        }
    }

    public sealed interface Option<T> permits BooleanOption, IntegerOption, ChoiceOption {
        Details details();
        T defaultValue();
        boolean accepts(Object value);

        /** Missing or outdated persisted values use the descriptor's default. */
        @SuppressWarnings("unchecked")
        default T read(Map<String, ?> snapshot) {
            Object value = snapshot.get(details().id());
            return accepts(value) ? (T) value : defaultValue();
        }

        /** Evaluate against a complete snapshot such as {@link Registration#read()}, including staged edits. */
        default boolean enabled(Map<String, ?> snapshot) {
            return details().requires().stream().allMatch(c -> c.value().equals(snapshot.get(c.optionId())));
        }
    }

    public record BooleanOption(Details details, boolean initial) implements Option<Boolean> {
        public BooleanOption { Objects.requireNonNull(details, "details"); }

        @Override public Boolean defaultValue() { return initial; }
        @Override public boolean accepts(Object value) { return value instanceof Boolean; }
    }

    /** Inclusive stepped range. Both endpoints and the default are reachable by the slider. */
    public record IntegerOption(Details details, int initial, int min, int max, int step) implements Option<Integer> {
        public IntegerOption {
            Objects.requireNonNull(details, "details");
            if (step <= 0 || min > max || ((long) max - min) % step != 0
                    || !inRange(initial, min, max, step)) {
                throw new IllegalArgumentException("Invalid integer range, step or default for " + details.id());
            }
        }

        @Override public Integer defaultValue() { return initial; }
        @Override public boolean accepts(Object value) {
            return value instanceof Integer n && inRange(n, min, max, step);
        }

        private static boolean inRange(int value, int min, int max, int step) {
            return value >= min && value <= max && ((long) value - min) % step == 0;
        }
    }

    /** A persisted choice is identified by its stable ID and can have a translated label. */
    public record Choice(String id, Text label) {
        public Choice {
            requireId(id, "choice");
            requireLabel(label, "choice label");
        }
    }

    public record ChoiceOption(Details details, String initial, List<Choice> choices) implements Option<String> {
        public ChoiceOption {
            Objects.requireNonNull(details, "details");
            choices = List.copyOf(choices);
            Set<String> ids = new HashSet<>();
            for (Choice choice : choices) {
                if (!ids.add(choice.id())) throw new IllegalArgumentException("Duplicate choice " + choice.id());
            }
            if (!ids.contains(initial)) throw new IllegalArgumentException("Invalid choice default for " + details.id());
        }

        @Override public String defaultValue() { return initial; }
        @Override public boolean accepts(Object value) {
            return value instanceof String id && choices.stream().anyMatch(choice -> choice.id().equals(id));
        }
    }

    /** The add-on owns the current values, persistence and thread safety of its configuration. */
    public interface Storage {
        /** Current settings by local ID. Return a stable snapshot for this call. */
        Map<String, ?> read();

        /** Commit the entire immutable patch or throw an exception while retaining the previous state. */
        void apply(Map<String, Object> changes);
    }

    public record Registration(String namespace, Text title, List<Option<?>> options, Storage storage) {
        public Registration {
            requireId(namespace, "namespace");
            if (RESERVED_NAMESPACES.contains(namespace)) throw new IllegalArgumentException("Reserved namespace " + namespace);
            requireLabel(title, "add-on title");
            options = List.copyOf(options);
            Objects.requireNonNull(storage, "storage");
            if (options.isEmpty()) throw new IllegalArgumentException("An add-on needs at least one setting");
            validateOptions(indexOptions(options));
        }

        /** Reads once and returns an immutable, complete snapshot using defaults for missing or invalid values. */
        public Map<String, Object> read() {
            Map<String, ?> stored = Objects.requireNonNull(storage.read(), "Storage.read() returned null");
            Map<String, Object> snapshot = new LinkedHashMap<>();
            for (Option<?> option : options) snapshot.put(option.details().id(), option.read(stored));
            return Collections.unmodifiableMap(snapshot);
        }

        /**
         * Validates the whole patch before calling storage once. Availability describes the UI, so a preference can
         * stay saved when another setting temporarily disables its control.
         */
        public void apply(Map<String, ?> changes) {
            if (changes.isEmpty()) return;
            Map<String, Option<?>> byId = indexOptions(options);
            Map<String, Object> patch = new LinkedHashMap<>();
            changes.forEach((id, value) -> {
                Option<?> option = byId.get(id);
                if (option == null || !option.accepts(value)) {
                    throw new IllegalArgumentException("Invalid setting in patch for " + namespace + ":" + id);
                }
                patch.put(id, value);
            });
            storage.apply(Collections.unmodifiableMap(patch));
        }
    }

    private static Map<String, Option<?>> indexOptions(List<Option<?>> options) {
        Map<String, Option<?>> byId = new LinkedHashMap<>();
        for (Option<?> option : options) {
            String id = option.details().id();
            if (byId.putIfAbsent(id, option) != null) throw new IllegalArgumentException("Duplicate setting " + id);
        }
        return byId;
    }

    private static void validateOptions(Map<String, Option<?>> byId) {
        for (Option<?> option : byId.values()) {
            Set<String> dependencies = new HashSet<>();
            for (Condition condition : option.details().requires()) {
                Option<?> dependency = byId.get(condition.optionId());
                if (dependency == null || !dependency.accepts(condition.value()) || !dependencies.add(condition.optionId())) {
                    throw new IllegalArgumentException("Invalid dependency " + condition.optionId()
                        + " for " + option.details().id());
                }
            }
        }
        Set<String> pending = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : byId.keySet()) visit(id, byId, pending, visited);
    }

    private static void visit(String id, Map<String, Option<?>> byId, Set<String> pending, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!pending.add(id)) throw new IllegalArgumentException("Cyclic setting dependencies at " + id);
        for (Condition condition : byId.get(id).details().requires()) {
            visit(condition.optionId(), byId, pending, visited);
        }
        pending.remove(id);
        visited.add(id);
    }

    private static void requireId(String id, String kind) {
        if (id == null || !ID.matcher(id).matches() || id.contains("..")) {
            throw new IllegalArgumentException("Invalid " + kind + " ID: " + id);
        }
    }

    private static void requireLabel(Text text, String kind) {
        Objects.requireNonNull(text, kind);
        if (text.fallback().isBlank()) throw new IllegalArgumentException("Empty " + kind);
    }
}
