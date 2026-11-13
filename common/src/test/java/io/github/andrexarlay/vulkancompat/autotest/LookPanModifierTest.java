package io.github.andrexarlay.vulkancompat.autotest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookPanModifierTest {
    @Test
    void parserAcceptsPositiveAndNegativePan() {
        var steps = LookScenario.parseSteps("I:path+t18000+dn=rr+d512+pan=10+f300,W:path+pan=-25+f1,G:path");
        assertEquals(10, steps.get(0).pan());
        assertEquals(300, steps.get(0).frames());
        assertEquals(-25, steps.get(1).pan());
        assertEquals(0, steps.get(2).pan());
    }

    @Test
    void parserRejectsNonNumericPan() {
        assertThrows(IllegalArgumentException.class, () -> LookScenario.parseSteps("I:path+pan=abc"));
        assertThrows(IllegalArgumentException.class, () -> LookScenario.parseSteps("I:path+pan="));
        assertThrows(IllegalArgumentException.class, () -> LookScenario.parseSteps("I:path+pan=1.5"));
    }

    @Test
    void nextYawAdvancesByCentidegrees() {
        assertEquals(0.1f, LookScenario.nextYaw(0f, 10), 1e-5f);
        assertEquals(179.75f, LookScenario.nextYaw(180f, -25), 1e-4f);
        assertEquals(-90.25f, LookScenario.nextYaw(-90f, -25), 1e-4f);
    }

    @Test
    void nextYawWrapsToMinus180To180() {
        assertEquals(-179.95f, LookScenario.nextYaw(179.95f, 10), 1e-3f);
        assertEquals(179.95f, LookScenario.nextYaw(-179.95f, -10), 1e-3f);
        float y = 0f;
        for (int i = 0; i < 3600; i++) {   // 360 deg at 0.1 deg/frame: back to the start, always in range
            y = LookScenario.nextYaw(y, 10);
            assertTrue(y >= -180f && y < 180f, "yaw out of range: " + y);
        }
        assertEquals(0f, y, 0.05f);
    }

    @Test
    void panContinuesOnlyForConsecutivePanStepsOfTheSameScene() {
        var steps = LookScenario.parseSteps("I:path+pan=10+f300,I:path+pan=10+f1,W:path+pan=10,I:path+f1");
        assertFalse(LookScenario.continuesPan(null, 0, steps.get(0)));      // first pan step places the camera
        assertTrue(LookScenario.continuesPan("I", 10, steps.get(1)));       // following pan step keeps the yaw
        assertFalse(LookScenario.continuesPan("I", 10, steps.get(2)));      // other scene: re-place
        assertFalse(LookScenario.continuesPan("I", 10, steps.get(3)));      // no pan: stop
    }

    @Test
    void applyAimSetsYRotOToThePreviousYawWhenPanning() throws Exception {
        String src = Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/autotest/LookScenario.java"));
        Matcher m = Pattern.compile("private void applyAim\\(AutotestContext ctx\\) \\{(.*?)\n    }\n", Pattern.DOTALL)
            .matcher(src.replace("\r\n", "\n"));
        assertTrue(m.find(), "applyAim not found");
        String body = m.group(1);
        assertTrue(body.contains("float prevYaw = aimYaw;"), "previous yaw must be captured before advancing");
        assertTrue(body.contains("nextYaw(prevYaw, pan)"), "yaw must advance from the previous one");
        assertTrue(body.contains("player.yRotO = prevYaw;"), "yRotO must be the previous frame's yaw while panning");
        assertTrue(body.indexOf("float prevYaw = aimYaw;") < body.indexOf("aimYaw = yaw;"), "prevYaw read after aimYaw was advanced");
    }
}
