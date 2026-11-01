package io.github.andrexarlay.vulkancompat.rt.world;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The light shafts march along the ray the path tracer shoots through the same pixel. RtSlangFrameData already folds the
 * renderer's y flip into the camera basis (its "up" is minus the direction towards the bottom edge), and
 * pathTracePrimaryRay uses that basis as it is. The volumetric march mirrored ndc.y a second time, so with flipY the
 * shafts were marched through the vertically mirrored pixel: the rays of the sky lit the ground and the other way round.
 * Only the history reprojection mirrors, because it starts from a matrix NDC, not from the basis.
 */
class RtSlangVolumetricFlipTest {
    private static final Path ENTRY = Path.of("src/main/resources/shaders/slang/light/volumetric_march_entry.slang");
    private static final Path VOLUMETRIC = Path.of("src/main/resources/shaders/slang/light/volumetric.slang");

    @Test
    void theBasisAlreadyCarriesTheFlip() {
        RtFrameParams p = new RtFrameParams();
        p.invViewProj.set(new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f).invert());
        RtPathTraceSlangPass.Frame flipped = new RtPathTraceSlangPass.Frame();
        RtPathTraceSlangPass.Frame straight = new RtPathTraceSlangPass.Frame();
        p.flipY = true;
        RtSlangFrameData.fill(flipped, p, 16, 9, 0, 0);
        p.flipY = false;
        RtSlangFrameData.fill(straight, p, 16, 9, 0, 0);
        assertEquals(-straight.up.y, flipped.up.y, 1e-6f, "the flip is in the basis: one more mirror undoes it");
    }

    @Test
    void theMarchUsesTheBasisAsThePathTracerDoes() throws IOException {
        String entry = Files.readString(ENTRY).replace("\r\n", "\n");
        int ndc = entry.indexOf("float2 ndc = (float2(pf) + 0.5) / fullSize * 2.0 - 1.0;");
        int dir = entry.indexOf("float3 dir = normalize(forward + right * (ndc.x * tanHalf * aspect) - up * (ndc.y * tanHalf));");
        assertTrue(ndc > 0 && dir > ndc);
        assertFalse(entry.substring(ndc, dir).contains("ndc.y = -ndc.y"), "no second mirror between the pixel and the ray");
        assertTrue(entry.contains("pc.prevOriginDelta, rayEnd, float2(fullSize), pc.flipY != 0u, ok);"),
            "the history reprojection still mirrors the matrix NDC");
        String volumetric = Files.readString(VOLUMETRIC).replace("\r\n", "\n");
        assertTrue(volumetric.contains("    if (flipY) ndc.y = -ndc.y;"), "shaftHistoryPixel keeps its mirror");
    }
}
