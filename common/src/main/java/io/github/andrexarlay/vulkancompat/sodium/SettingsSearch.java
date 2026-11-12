package io.github.andrexarlay.vulkancompat.sodium;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class SettingsSearch {
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private SettingsSearch() {}

    static String index(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replace('ё', 'е');
        return SEPARATORS.matcher(normalized).replaceAll(" ").strip();
    }

    static List<String> terms(String query) {
        String normalized = index(query);
        return normalized.isEmpty() ? List.of() : List.of(normalized.split(" "));
    }

    static boolean matches(String index, List<String> terms) {
        return !terms.isEmpty() && terms.stream().allMatch(index::contains);
    }
}
