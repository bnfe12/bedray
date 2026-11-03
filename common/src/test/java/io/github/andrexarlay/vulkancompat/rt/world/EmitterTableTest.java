package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class EmitterTableTest {
    private static float[] light(float x) {
        float[] r = new float[25];
        r[0] = x; r[3] = 1; r[4] = 1; r[7] = 1; r[9] = 1; r[14] = 1;
        for (int i = 20; i < 24; i++) r[i] = 1;
        return r;
    }
    private static int id(EmitterTable.Publication p, int index) {
        return p.data().getInt(EmitterTable.HEADER_BYTES + index * EmitterTable.RECORD_BYTES + RtEmitters.BYTES);
    }
    @Test void orderImportanceAndReanchoringPreserveEachLightIdentity() {
        var table = new EmitterTable();
        float[] a = light(100), b = light(101);
        var first = table.prepare(new float[][]{a,b}, 2, 0,0,0); table.commit(first);
        a = a.clone(); b = b.clone(); a[0] -= 64; b[0] -= 64; a[24] = 999;
        var reordered = table.prepare(new float[][]{b,a}, 2, 64,0,0);
        assertEquals(id(first,0), id(reordered,1)); assertEquals(id(first,1), id(reordered,0));
        assertEquals(first.revision(), reordered.revision()); assertEquals(2, reordered.retained());
        assertEquals(0, reordered.added()); assertEquals(0, reordered.removed());
    }
    @Test void sameSizedReplacementRejectsOnlyTheChangedLight() {
        var table = new EmitterTable();
        var first = table.prepare(new float[][]{light(0),light(1)},2,0,0,0); table.commit(first);
        var next = table.prepare(new float[][]{light(0),light(2)},2,0,0,0);
        assertEquals(id(first,0), id(next,0)); assertNotEquals(id(first,1), id(next,1));
        assertEquals(1, next.retained()); assertEquals(1, next.added()); assertEquals(1, next.removed());
        assertNotEquals(first.revision(), next.revision());
    }
    @Test void everyCapturedMaterialGeometryAndTintWordParticipatesButImportanceDoesNot() {
        var table = new EmitterTable();
        float[] original = light(0);
        var first = table.prepare(new float[][]{original},1,0,0,0); table.commit(first);
        for (int word=0; word<24; word++) {
            float[] changed = original.clone(); changed[word] += 0.25f;
            assertNotEquals(id(first,0), id(table.prepare(new float[][]{changed},1,0,0,0),0), "word " + word);
        }
        float[] reranked = original.clone(); reranked[24] = 1000;
        assertEquals(id(first,0), id(table.prepare(new float[][]{reranked},1,0,0,0),0));
    }
    @Test void removedThenReintroducedLightsNeverReuseRetiredIds() {
        var table = new EmitterTable();
        var first = table.prepare(new float[][]{light(0)},1,0,0,0); table.commit(first);
        table.commit(table.prepare(new float[0][],0,0,0,0));
        var returned = table.prepare(new float[][]{light(0)},1,0,0,0);
        assertNotEquals(id(first,0), id(returned,0));
    }
    @Test void identicalQuadsStillHaveDistinctSampleIdentities() {
        var table = new EmitterTable();
        var p = table.prepare(new float[][]{light(0),light(0)},2,0,0,0); table.commit(p);
        assertNotEquals(id(p,0), id(p,1));
        var next = table.prepare(new float[][]{light(0),light(0)},2,0,0,0);
        assertEquals(id(p,0),id(next,0)); assertEquals(id(p,1),id(next,1));
    }
    @Test void atlasReplacementInvalidatesIdsEvenWhenCapturedPayloadIsUnchanged() {
        var table = new EmitterTable();
        float[][] records = {light(0)};
        var first = table.prepare(records,1,0,0,0,1); table.commit(first);
        var changed = table.prepare(records,1,0,0,0,2);
        assertNotEquals(id(first,0),id(changed,0));
        assertEquals(0,changed.retained()); assertEquals(1,changed.removed());
    }
    @Test void worldCornersRetainPrecisionPastFloatIntegerRange() {
        var table = new EmitterTable();
        var p = table.prepare(new float[][]{light(0),light(1)},2,30_000_000,0,0); table.commit(p);
        assertNotEquals(id(p,0),id(p,1));
        var anchored = table.prepare(new float[][]{light(-16),light(-15)},2,30_000_016,0,0);
        assertEquals(id(p,0),id(anchored,0)); assertEquals(id(p,1),id(anchored,1));
    }
    @Test void serializedHeaderPayloadAndCollisionChainStayWithinTheDeclaredRange() {
        var table = new EmitterTable();
        float[][] initial = new float[10][];
        for(int i=0;i<10;i++) initial[i] = light(i);
        table.commit(table.prepare(initial,10,0,0,0));
        var p = table.prepare(new float[][]{initial[0],initial[8]},2,0,0,0);
        ByteBuffer data = p.data();
        assertTrue(data.isReadOnly()); assertEquals(EmitterTable.bytes(2),data.remaining());
        assertEquals(EmitterTable.MAGIC,data.getInt(0)); assertEquals(2,data.getInt(4));
        assertEquals(4,data.getInt(8)); assertEquals(112,data.getInt(12));
        for(int r=0;r<2;r++) for(int w=0;w<24;w++)
            assertEquals(initial[r*8][w],data.getFloat(16+r*112+w*4));
        // IDs 1 and 9 collide at capacity 4. Both entries must exist with distinct current physical slots.
        int start=16+2*112, found=0;
        for(int s=0;s<4;s++) if(data.getInt(start+s*16)!=0) {
            int index=data.getInt(start+s*16+4);
            assertTrue(index>=0 && index<2); assertEquals(id(p,index),data.getInt(start+s*16)); found++;
        }
        assertEquals(2,found); assertEquals(2,p.maxProbe());
    }
    @Test void invalidAndNonFiniteInputsAreRejectedAndMaximumStorageIsBounded() {
        var table = new EmitterTable();
        assertThrows(IllegalArgumentException.class,()->table.prepare(new float[0][],1,0,0,0));
        assertThrows(IllegalArgumentException.class,()->EmitterTable.bytes(65537));
        float[] bad = light(0); bad[20] = Float.NaN;
        assertThrows(IllegalArgumentException.class,()->table.prepare(new float[][]{bad},1,0,0,0));
        assertEquals(160,EmitterTable.bytes(1)); assertEquals(480,EmitterTable.bytes(3));
        assertEquals(9*1024*1024+16,EmitterTable.bytes(65536));
    }
}
