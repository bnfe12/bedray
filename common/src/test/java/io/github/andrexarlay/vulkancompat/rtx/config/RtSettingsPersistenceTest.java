package io.github.andrexarlay.vulkancompat.rtx.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The experimental switches and the Neural Rendering controls must survive a restart. Until they were written by
 * {@link RtSettings#write}, {@code dlss_neural_rendering} (which needs a restart to take effect) and {@code hand_geometry}
 * were read from bedray.toml but never saved, so choosing them on the settings screen did nothing after the next launch.
 */
class RtSettingsPersistenceTest {
    private static final List<String> KEYS = List.of(
        "dlss_neural_rendering", "dlss_nr_intensity", "dlss_nr_tone_strength", "dlss_nr_structure_strength",
        "dlss_nr_skin_structure_strength", "dlss_nr_auto_mask", "dlss_nr_model", "hand_geometry", "icon_light_sky", "addon_lights");

    private static String written() throws IOException {
        StringWriter out = new StringWriter();
        RtSettings.write(out);
        return out.toString();
    }

    @Test
    void experimentalSwitchesAndNeuralRenderingControlsAreWritten() throws IOException {
        String text = written();
        for (String key : KEYS) {
            assertTrue(text.contains("\n" + key + " = "), key + " is registered but not written to bedray.toml");
        }
    }

    @Test
    void writtenValuesReadBackThroughTheConfigParser() throws IOException {
        boolean nr = RtSettings.dlssNeuralRendering;
        boolean hand = RtSettings.handGeometry;
        int intensity = RtSettings.dlssNrIntensity;
        int model = RtSettings.dlssNrModel;
        try {
            RtSettings.dlssNeuralRendering = true;
            RtSettings.handGeometry = true;
            RtSettings.dlssNrIntensity = 40;
            RtSettings.dlssNrModel = 3;
            String text = written();
            RtSettings.dlssNeuralRendering = false;
            RtSettings.handGeometry = false;
            RtSettings.dlssNrIntensity = 100;
            RtSettings.dlssNrModel = 1;
            for (String line : text.split("\n")) {
                int eq = line.indexOf(" = ");
                if (eq > 0 && KEYS.contains(line.substring(0, eq))) {
                    RtSettings.applyKeyValue(line.substring(0, eq), line.substring(eq + 3));
                }
            }
            assertTrue(RtSettings.dlssNeuralRendering);
            assertTrue(RtSettings.handGeometry);
            assertEquals(40, RtSettings.dlssNrIntensity);
            assertEquals(3, RtSettings.dlssNrModel);
        } finally {
            RtSettings.dlssNeuralRendering = nr;
            RtSettings.handGeometry = hand;
            RtSettings.dlssNrIntensity = intensity;
            RtSettings.dlssNrModel = model;
        }
    }

    @Test
    void neuralRenderingStrengthsStayInTheRangeTheWrapperAccepts() {
        int before = RtSettings.dlssNrIntensity;
        try {
            RtSettings.applyKeyValue("dlss_nr_intensity", "250"); // out of range: kept as is
            assertEquals(before, RtSettings.dlssNrIntensity);
            RtSettings.applyKeyValue("dlss_nr_intensity", "100");
            assertEquals(1.0f, RtSettings.dlssNrIntensity());
        } finally {
            RtSettings.dlssNrIntensity = before;
        }
    }
}
