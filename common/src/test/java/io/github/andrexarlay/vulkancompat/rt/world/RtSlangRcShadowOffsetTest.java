package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RtSlangRcShadowOffsetTest {
    @Test void aViewerFacingMappedNormalCannotMakeTheReceiverShadowItself() throws Exception {
        // Receiver plane y=0, viewer and light above it. At grazing incidence
        // the view-facing mapped normal can point below the geometric face.
        double bias = 0.001;
        double mappedX = 0.99, mappedY = -0.1;
        double length = Math.hypot(mappedX, mappedY);
        mappedX /= length;
        mappedY /= length;
        assertTrue(mappedX + 0.2 * mappedY > 0, "mapped normal faces this viewer");
        double lightY = 0.8;
        double oldPlaneHit = -(mappedY * bias) / lightY;
        assertTrue(oldPlaneHit > 0 && oldPlaneHit < 1,
            "the mapped offset makes the receiver itself block the light");
        assertTrue(-bias / lightY < 0,
            "the geometric offset puts that plane behind the shadow ray");

        String shade = Files.readString(Path.of("src/main/resources/shaders/slang/radiance_cascades/rc_trace_shade.slang"));
        String compact = shade.replaceAll("\\s+", "");
        assertTrue(compact.contains("shadowNormal=safeNormalize(surface.hit.geometricNormal,normal)"));
        assertTrue(compact.contains("if(dot(shadowNormal,view)<0.0)shadowNormal=-shadowNormal;"),
            "back-face receivers keep the same view-facing offset convention");
        assertTrue(compact.contains("traceShadowVisibility(traversal,atlas,blockMaterials,surface.hit.position,shadowNormal,"));
        assertFalse(compact.contains("traceShadowVisibility(traversal,atlas,blockMaterials,surface.hit.position,normal,"));
        assertTrue(compact.contains("bsdfEvaluate(surface.material,view,normal,light.direction)"),
            "shading and sampling still use the mapped normal");
        assertTrue(compact.contains("lightReceiver(surface.hit.position,normal,surface.material.emissionMask)"));
    }
}
