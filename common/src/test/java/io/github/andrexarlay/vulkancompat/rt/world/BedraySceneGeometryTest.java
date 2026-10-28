package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneLayouts;
import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.SodiumMeshDecoder;
import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BedraySceneGeometryTest {
    @Test void capturePackingKeepsPassLocalIndicesAndVertexAttributes() {
        ByteBuffer solid = ByteBuffer.allocate(2 * 4 * 20).order(ByteOrder.nativeOrder());
        ByteBuffer translucent = ByteBuffer.allocate(4 * 20).order(ByteOrder.nativeOrder());
        for (ByteBuffer data : new ByteBuffer[]{solid, translucent}) {
            for (int vertex = 0; vertex < data.capacity() / 20; vertex++) {
                data.putInt(vertex * 20 + 8, 0xff805020);
                data.putInt(vertex * 20 + 16, 0x0010f8f8);
            }
        }
        DecodedSection decoded = SodiumMeshDecoder.decode(9, 2, -1, 3,
            new ByteBuffer[]{solid, null, translucent});
        try (Arena arena = Arena.ofConfined()) {
            var section = BedraySceneGeometry.build(decoded,
                new int[]{RtWorld.VEGETATION_BIT, 0, RtWorld.VEGETATION_BIT | RtWorld.LEAF_CUBE_BIT}, arena, 7, 11);
            assertEquals(12, section.vertexCount());
            assertEquals(18, section.indexCount());
            assertEquals(2, section.geometryCount());
            assertEquals(2, section.quadStart1());
            assertEquals(2 << 2, section.passBits());
            assertEquals(BedraySceneLayouts.SECTION_FLAG_GEOMETRY_VERTEX, section.flags());
            assertArrayEquals(new int[]{0,1,2,2,3,0,4,5,6,6,7,4,0,1,2,2,3,0}, section.indices().toArray(JAVA_INT));
            for (int vertex = 0; vertex < 12; vertex++) {
                long at = vertex * 64L;
                assertEquals(decoded.positions[vertex * 3], section.vertices().get(JAVA_FLOAT, at));
                assertEquals(decoded.uvs[vertex * 2], section.vertices().get(JAVA_FLOAT, at + 16));
                assertEquals((decoded.colors[vertex] & 255) / 255f,
                    section.vertices().get(JAVA_FLOAT, at + 32), 0.00001f);
                assertEquals((decoded.light[vertex] >>> 16) & 255, section.vertices().get(JAVA_INT, at + 48));
                assertEquals(vertex < 4 ? 1 : vertex < 8 ? 0 : 3, section.vertices().get(JAVA_INT, at + 52));
            }
        }
    }
}
