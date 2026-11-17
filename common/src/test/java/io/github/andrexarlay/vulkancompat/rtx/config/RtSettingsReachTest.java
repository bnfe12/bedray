package io.github.andrexarlay.vulkancompat.rtx.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Every option the settings registry reads through a getter reaches code outside the config package. The six DLSS-NR
 * options ("DLSS 5 Neural Rendering") were stored, parsed, written to bedray.toml and reset, but no renderer read them:
 * the package behind them was a CPU colour mixer that nothing called. They are gone, and a new getter that nothing reads
 * fails here instead of becoming a switch that changes nothing. The current {@code dlss_nr_*} options are different: each is
 * read by {@code DlssNeuralRendering} and sent to the NGX evaluation, which the last test below pins.
 */
class RtSettingsReachTest {
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path SETTINGS = SOURCES.resolve("io/github/andrexarlay/vulkancompat/rtx/config/RtSettings.java");
    private static final Pattern GETTER =
        Pattern.compile("public static \\w+ (\\w+)\\(\\) \\{ return [bi]\\(\"([a-z0-9_]+)\"\\); \\}");

    @Test
    void everyRegisteredGetterIsReadOutsideTheConfigPackage() throws IOException {
        String settings = Files.readString(SETTINGS);
        List<String> outside = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.toString().replace('\\', '/').contains("/rtx/config/")) continue;
                outside.add(Files.readString(file));
            }
        }
        List<String> unread = new ArrayList<>();
        Matcher getter = GETTER.matcher(settings);
        while (getter.find()) {
            Pattern call = Pattern.compile("\\b" + getter.group(1) + "\\(\\)");
            if (outside.stream().noneMatch(source -> call.matcher(source).find()))
                unread.add(getter.group(1) + " (" + getter.group(2) + ")");
        }
        assertEquals(List.of(), unread, "options no renderer reads");
    }

    @Test
    void theNeuralRenderingStubIsGone() throws IOException {
        assertFalse(Files.exists(SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt/upscale/dlss/nr")));
        assertFalse(Files.readString(SETTINGS).contains("dlss_neural_intensity"), "no stored, parsed or written stub DLSS-NR option");
    }

    @Test
    void everyNeuralRenderingOptionIsSentToTheEvaluation() throws IOException {
        String settings = Files.readString(SETTINGS);
        String evaluation = Files.readString(SOURCES.resolve(
            "io/github/andrexarlay/vulkancompat/rtx/upscaler/dlss/DlssNeuralRendering.java"));
        Matcher key = Pattern.compile("(?:integer|bool)\\(\"(dlss_nr_[a-z_]+)\"").matcher(settings);
        List<String> unused = new ArrayList<>();
        int found = 0;
        while (key.find()) {
            found++;
            String accessor = "RtSettings.dlssNr" + camel(key.group(1).substring("dlss_nr_".length())) + "()";
            if (!evaluation.contains(accessor)) unused.add(key.group(1) + " -> " + accessor);
        }
        assertEquals(6, found, "the six Neural Rendering controls are registered");
        assertEquals(List.of(), unused, "controls the Neural Rendering evaluation does not read");
    }

    private static String camel(String snake) {
        StringBuilder out = new StringBuilder();
        for (String part : snake.split("_")) out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return out.toString();
    }
}
