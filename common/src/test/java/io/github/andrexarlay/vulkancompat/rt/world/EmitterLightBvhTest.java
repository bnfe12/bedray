package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class EmitterLightBvhTest {
    private static float[] light(float x) {
        float[] r = new float[25];
        r[0]=x; r[3]=1; r[4]=1; r[7]=1; r[9]=1; r[14]=1;
        Arrays.fill(r,20,24,1);
        return r;
    }
    private static void verify(EmitterTable.Publication publication) {
        int count=publication.count(), nodes=2*count-1;
        var data=publication.data();
        int start=EmitterTable.HEADER_BYTES+count*112+EmitterTable.capacity(count)*16;
        assertEquals(nodes,data.getInt(16)); assertEquals(48,data.getInt(20));
        assertTrue(publication.bvhDepth()<=16);
        boolean[] leafSeen=new boolean[count];
        for(int node=0;node<nodes;node++) {
            int at=start+node*48;
            assertTrue(Float.isFinite(data.getFloat(at+12))); assertTrue(data.getFloat(at+12)>0);
            assertEquals(node==0, data.getInt(at+40)==-1);
            int leaf=data.getInt(at+44);
            if(leaf!=-1) {
                assertTrue(leaf>=0 && leaf<count); assertFalse(leafSeen[leaf]); leafSeen[leaf]=true;
                assertEquals(node,data.getInt(EmitterTable.HEADER_BYTES+leaf*112+100));
            } else for(int word=32;word<=36;word+=4) {
                int child=data.getInt(at+word);
                assertTrue(child>node && child<nodes); int c=start+child*48;
                assertEquals(node,data.getInt(c+40));
                for(int axis=0;axis<3;axis++) {
                    assertTrue(data.getFloat(at+axis*4)<=data.getFloat(c+axis*4));
                    assertTrue(data.getFloat(at+16+axis*4)>=data.getFloat(c+16+axis*4));
                }
            }
        }
        for(boolean seen:leafSeen) assertTrue(seen);
    }
    @Test void mortonLanePreservesTheBitOrderForEveryTenBitCoordinate() {
        for (int coordinate = 0; coordinate < 1024; coordinate++) {
            int expected = 0;
            for (int bit = 0; bit < 10; bit++) {
                expected |= ((coordinate >>> bit) & 1) << (3 * bit);
            }
            assertEquals(expected, EmitterLightBvh.mortonLane(coordinate));
        }
    }

    @Test void balancedSpatialTreeCoversSkewedQuadsAndFinitePower() {
        float[][] records={light(100),light(-5),light(0),light(7),light(2)};
        records[1][8]=2; records[1][9]=-3; records[1][4]=-4;
        records[4][14]=Float.MAX_VALUE; records[4][7]=Float.MAX_VALUE;
        var p=new EmitterTable(true).prepare(records,records.length,0,0,0);
        verify(p); assertEquals(0.125f,p.globalFraction());
        int start=EmitterTable.HEADER_BYTES+records.length*112+EmitterTable.capacity(records.length)*16;
        assertTrue(p.data().getFloat(start)<=-9);
        assertTrue(p.data().getFloat(start+16)>=101);
    }
    @Test void reorderingAndReanchoringReuseTheCommittedTopologyWithoutChangingIds() {
        var table=new EmitterTable(true);
        float[][] records={light(100),light(0),light(25)};
        var first=table.prepare(records,3,0,0,0); table.commit(first);
        float[][] reordered={records[2].clone(),records[0].clone(),records[1].clone()};
        for(var r:reordered) r[0]-=64;
        var second=table.prepare(reordered,3,64,0,0);
        verify(second); assertTrue(second.topologyReused());
        assertArrayEquals(first.spatialOrder(),second.spatialOrder());
        assertEquals(first.revision(),second.revision());
        reordered[0][0]+=1;
        var changed=table.prepare(reordered,3,64,0,0);
        verify(changed); assertFalse(changed.topologyReused());
    }
    @Test void maximumLightCountHasBoundedDepthAndMemory() {
        float[][] records=new float[65536][];
        for(int i=0;i<records.length;i++) records[i]=light(i%256);
        var p=new EmitterTable(true).prepare(records,records.length,0,0,0);
        verify(p); assertEquals(16,p.bvhDepth());
        assertEquals(15*1024*1024-16,p.data().remaining());
    }
    @Test void referenceModeRetainsTheGlobalDistributionAndTheSameTableContract() {
        var p=new EmitterTable(false).prepare(new float[][]{light(0)},1,0,0,0);
        verify(p); assertEquals(1f,p.globalFraction()); assertEquals(0,p.bvhDepth());
    }
    @Test void overflowInQuadBoundsCannotPublishAnInvalidTree() {
        float[] r=light(Float.MAX_VALUE); r[4]=Float.MAX_VALUE;
        assertThrows(IllegalArgumentException.class,()->new EmitterTable().prepare(new float[][]{r},1,0,0,0));
    }
}
