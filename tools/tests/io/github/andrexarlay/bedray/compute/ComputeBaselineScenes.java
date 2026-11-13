package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.bedray.api.v1.BlockModelGeometry;
import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;
import io.github.andrexarlay.bedray.api.v1.CubeFaceMaterial;
import io.github.andrexarlay.bedray.api.v1.CubeMaterial;
import io.github.andrexarlay.bedray.api.v1.CubeSectionMaterials;
import io.github.andrexarlay.bedray.api.v1.LightingFrame;
import io.github.andrexarlay.bedray.api.v1.ModelQuad;
import io.github.andrexarlay.bedray.api.v1.SectionLightData;
import io.github.andrexarlay.bedray.api.v1.SectionModelGeometry;
import io.github.andrexarlay.bedray.api.v1.SectionSnapshot;
import io.github.andrexarlay.bedray.api.v1.SectionTintData;
import io.github.andrexarlay.bedray.compute.voxel.VoxelDda;
import io.github.andrexarlay.bedray.compute.voxel.VoxelGrid;
import io.github.andrexarlay.bedray.compute.voxel.VoxelGridBuilder;
import io.github.andrexarlay.bedray.compute.voxel.VoxelHit;

import java.util.ArrayList;
import java.util.List;

/**
 * Three fixed offscreen baseline scenes for the V0 measurement run: an open field with a biome border,
 * grass, leaves and water; a dark room with torches, lava and glowstone; a cave deeper than 48 blocks.
 * Every scene fills all 27 sections of the resident window, uses one fixed camera and one lighting frame,
 * and synthesizes reverse-Z raster depth from a CPU DDA oracle exactly like the in-game composition path.
 * Cameras must sit in window section (1,1,1): the upload origin is floor(camera/16)-1, so any other
 * section shifts the window and leaves nine tiles unknown.
 */
public final class ComputeBaselineScenes {
    public static final float NEAR = 0.05f, FAR = 1000f, HALF_FOV_Y_DEGREES = 35f;
    private static final float[] IDENTITY = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    private static final CubeFaceMaterial FACE = new CubeFaceMaterial(0, 0, 1, 0, 0, 1, 0xFFFFFFFF, false);
    private static final CubeFaceMaterial TOP = new CubeFaceMaterial(0, 0, 1, 0, 0, 1, 0xFFFFFFFF, true);
    private static final CubeMaterial CUBE = new CubeMaterial(List.of(FACE, TOP, FACE, FACE, FACE, FACE));
    private static final BlockModelGeometry WATER = new BlockModelGeometry(List.of(
        new ModelQuad(new float[]{0,1,0,0,0, 0,1,1,0,1, 1,1,1,1,1, 1,1,0,1,0}, 0xFFFFFFFF,
            ModelQuad.TRANSLUCENT | ModelQuad.CELL_TINT | ModelQuad.FLUID, 0)));
    private static final BlockModelGeometry LEAVES = new BlockModelGeometry(List.of(
        cubeQuad(0,0,0, 1,0,0, 1,0,1, 0,0,1), cubeQuad(0,1,0, 0,1,1, 1,1,1, 1,1,0),
        cubeQuad(0,0,0, 0,1,0, 1,1,0, 1,0,0), cubeQuad(0,0,1, 1,0,1, 1,1,1, 0,1,1),
        cubeQuad(0,0,0, 0,0,1, 0,1,1, 0,1,0), cubeQuad(1,0,0, 1,1,0, 1,1,1, 1,0,1)));

    private ComputeBaselineScenes() {}

    public record Scene(String name, String description, List<SectionSnapshot> sections, VoxelGrid occupancy,
                        CameraSnapshot camera, LightingFrame lighting, int originX, int originY, int originZ) {}

    public static float[] projection(int width, int height) {
        float aspect = (float) width / height;
        float fy = (float) (1 / Math.tan(Math.toRadians(HALF_FOV_Y_DEGREES)));
        float a = NEAR / (FAR - NEAR), b = NEAR * FAR / (FAR - NEAR);
        return new float[]{fy / aspect,0,0,0, 0,-fy,0,0, 0,0,a,-1, 0,0,b,0};
    }

    public static float[] inverseProjection(int width, int height) {
        return invert(projection(width, height));
    }

    /** Reverse-Z D32 depth per pixel from the CPU DDA oracle; zero where the ray escapes the window. */
    public static float[] depthOracle(Scene scene, int width, int height) {
        return depthOracle(scene, scene.camera(), width, height);
    }

    public static float[] depthOracle(Scene scene, CameraSnapshot camera, int width, int height) {
        float[] inverse = camera.inverseProjection();
        float a = NEAR / (FAR - NEAR), b = NEAR * FAR / (FAR - NEAR);
        double ox = camera.x() - scene.originX(), oy = camera.y() - scene.originY(),
            oz = camera.z() - scene.originZ();
        float[] depth = new float[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            float ndcX = (x + 0.5f) / width * 2 - 1, ndcY = (y + 0.5f) / height * 2 - 1;
            float[] p = new float[4];
            for (int row = 0; row < 4; row++)
                p[row] = inverse[row] * ndcX + inverse[4 + row] * ndcY + inverse[8 + row] * 0.5f + inverse[12 + row];
            float[] ray = normalize(p[0] / p[3], p[1] / p[3], p[2] / p[3]);
            VoxelHit hit = VoxelDda.trace(scene.occupancy(), ox, oy, oz, ray[0], ray[1], ray[2], 0, 160);
            if (hit == null) continue;
            double viewZ = hit.t() * ray[2];
            depth[x + width * y] = (float) ((a * viewZ + b) / -viewZ);
        }
        return depth;
    }

    /** Open field: grass floor split by a biome border at x=24, a water pool, two cutout-leaf trees. */
    public static Scene fieldBiomeBorder() {
        Window w = new Window();
        int[][] trees = {{12, 12}, {36, 30}};
        for (int z = 0; z < 48; z++) for (int y = 0; y < 48; y++) for (int x = 0; x < 48; x++) {
            int kind = SectionSnapshot.EMPTY, cubeRef = 0, modelRef = 0, tint = 0;
            boolean pool = x >= 20 && x < 28 && z >= 20 && z < 28;
            if (y == 20 && !pool) { kind = SectionSnapshot.OPAQUE_CUBE; cubeRef = 1; tint = 0xFF000000 | (x < 24 ? 0x5FA04A : 0xA8A850); }
            if (y == 20 && pool) { kind = SectionSnapshot.MODEL; modelRef = 1; tint = 0xFF3F76E4; }
            for (int[] tree : trees) {
                if (x == tree[0] && z == tree[1] && y >= 21 && y <= 23) { kind = SectionSnapshot.OPAQUE_CUBE; cubeRef = 1; tint = 0xFF6B4A2B; }
                if (Math.abs(x - tree[0]) <= 1 && Math.abs(z - tree[1]) <= 1 && y >= 24 && y <= 26) {
                    kind = SectionSnapshot.MODEL; modelRef = 2; tint = 0xFF3E7A2E;
                }
            }
            w.set(x, y, z, kind, cubeRef, modelRef, tint, 0, y > 20 ? 15 : 0, 0);
        }
        return new Scene("field", "Open field, biome border at x=24, grass/leaves/water, day key light",
            w.sections(0, 0, 0), w.occupancy.build(),
            new CameraSnapshot(24.5, 26.5, 31.5, invert(projection(1280, 720)), IDENTITY), dayLighting(), 0, 0, 0);
    }

    /** Enclosed gray room: ceiling glowstone, four wall torches and a lava corner; no sky light. */
    public static Scene darkRoomEmitters() {
        int[][] emitters = {{24,27,24,15}, {10,20,10,14}, {38,20,10,14}, {10,20,38,14}, {38,20,38,14}};
        List<int[]> sources = new ArrayList<>(List.of(emitters));
        for (int x = 12; x <= 14; x++) for (int z = 12; z <= 14; z++) sources.add(new int[]{x, 17, z, 15});
        Window w = new Window();
        for (int z = 0; z < 48; z++) for (int y = 0; y < 48; y++) for (int x = 0; x < 48; x++) {
            boolean shell = (x == 8 || x == 40) && z >= 8 && z <= 40 && y >= 16 && y <= 28
                || (z == 8 || z == 40) && x >= 8 && x <= 40 && y >= 16 && y <= 28
                || (y == 16 || y == 28) && x >= 8 && x <= 40 && z >= 8 && z <= 40;
            int kind = shell ? SectionSnapshot.OPAQUE_CUBE : SectionSnapshot.EMPTY;
            int cubeRef = shell ? 1 : 0, tint = shell ? 0xFF8A8A8A : 0, emission = 0;
            if (x == 24 && y == 27 && z == 24) { kind = SectionSnapshot.OPAQUE_CUBE; cubeRef = 1; tint = 0xFFE8C860; emission = 15; }
            for (int[] torch : emitters)
                if (torch[3] == 14 && x == torch[0] && y == torch[1] && z == torch[2]) { kind = SectionSnapshot.UNSUPPORTED; cubeRef = 0; emission = 14; }
            if (y == 17 && x >= 12 && x <= 14 && z >= 12 && z <= 14) { kind = SectionSnapshot.UNSUPPORTED; cubeRef = 0; emission = 15; }
            int block = 0;
            for (int[] source : sources)
                block = Math.max(block, source[3] - Math.abs(x - source[0]) - Math.abs(y - source[1]) - Math.abs(z - source[2]));
            w.set(x, y, z, kind, cubeRef, 0, tint, Math.max(0, block), 0, emission);
        }
        return new Scene("darkroom", "Enclosed room, glowstone + 4 torches + lava corner, no sky light",
            w.sections(0, 0, 0), w.occupancy.build(),
            new CameraSnapshot(24.5, 22.5, 31.5, invert(projection(1280, 720)), IDENTITY), darkLighting(), 0, 0, 0);
    }

    /** Cave at world Y=-48 (deeper than the 48-block window): solid rock, carved cavern, lava pool. */
    public static Scene deepCave() {
        Window w = new Window();
        List<int[]> lava = new ArrayList<>();
        for (int z = 0; z < 48; z++) for (int y = 0; y < 48; y++) for (int x = 0; x < 48; x++) {
            double dx = (x - 24) / 10.0, dy = (y - 24) / 6.0, dz = (z - 24) / 10.0;
            boolean cavern = dx * dx + dy * dy + dz * dz <= 1;
            boolean tunnel = z >= 24 && z <= 40 && Math.abs(x - 24) < 3 && Math.abs(y - 24) < 3;
            boolean empty = cavern || tunnel;
            int kind = empty ? SectionSnapshot.EMPTY : SectionSnapshot.OPAQUE_CUBE;
            int cubeRef = empty ? 0 : 1, tint = empty ? 0 : 0xFF7A7A82, emission = 0;
            if (cavern && y <= 19 && y >= 18 && x >= 22 && x <= 26 && z >= 22 && z <= 26) {
                kind = SectionSnapshot.UNSUPPORTED; cubeRef = 0; emission = 15;
                lava.add(new int[]{x, y, z, 15});
            }
            int block = 0;
            for (int[] source : lava)
                block = Math.max(block, source[3] - Math.abs(x - source[0]) - Math.abs(y - source[1]) - Math.abs(z - source[2]));
            w.set(x, y, z, kind, cubeRef, 0, tint, Math.max(0, block), 0, emission);
        }
        return new Scene("cave", "Cave at world Y=-48 (deeper than 48 blocks), carved cavern, lava pool",
            w.sections(0, -3, 0), w.occupancy.build(),
            new CameraSnapshot(24.5, -22.5, 31.5, invert(projection(1280, 720)), IDENTITY), darkLighting(), 0, -48, 0);
    }

    private static LightingFrame dayLighting() {
        return new LightingFrame(new float[]{0.35f,0.85f,0.25f,1, 1,0.95f,0.9f,1, 0.25f,0.45f,1,1,
            1,0.7f,0.45f,1, 0.01f,0.01f,0.01f,0.5f, 0,0,0,0});
    }

    private static LightingFrame darkLighting() {
        return new LightingFrame(new float[]{0.35f,0.85f,0.25f,0, 1,0.95f,0.9f,0, 0.25f,0.45f,1,1,
            1,0.7f,0.45f,1, 0.005f,0.005f,0.008f,0.5f, 0,0,0,0});
    }

    private static ModelQuad cubeQuad(float ax, float ay, float az, float bx, float by, float bz,
                                      float cx, float cy, float cz, float dx, float dy, float dz) {
        return new ModelQuad(new float[]{ax,ay,az,0,0, bx,by,bz,0,1, cx,cy,cz,1,1, dx,dy,dz,1,0},
            0xFFFFFFFF, ModelQuad.CUTOUT | ModelQuad.CELL_TINT, 0);
    }

    private static float[] invert(float[] projection) {
        float[] inverse = new float[16];
        if (!TemporalHistory.invert(projection, inverse)) throw new IllegalStateException("Singular baseline projection");
        return inverse;
    }

    private static float[] normalize(float x, float y, float z) {
        float inverseLength = 1 / (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x * inverseLength, y * inverseLength, z * inverseLength};
    }

    /** Window-coordinate section builder; model reference 1 is water, 2 is the cutout leaf cube. */
    private static final class Window {
        final byte[][] cells = new byte[27][SectionSnapshot.CELL_COUNT];
        final short[][] cubes = new short[27][SectionSnapshot.CELL_COUNT];
        final short[][] models = new short[27][SectionSnapshot.CELL_COUNT];
        final short[][] light = new short[27][SectionSnapshot.CELL_COUNT];
        final int[][] tints = new int[27][SectionSnapshot.CELL_COUNT];
        final VoxelGridBuilder occupancy = VoxelGrid.builder(48, 48, 48);

        void set(int x, int y, int z, int kind, int cubeRef, int modelRef, int tint, int block, int sky, int emission) {
            int slot = x / 16 + 3 * (y / 16 + 3 * (z / 16));
            int index = x % 16 + 16 * (y % 16 + 16 * (z % 16));
            cells[slot][index] = (byte) kind;
            cubes[slot][index] = (short) cubeRef;
            models[slot][index] = (short) modelRef;
            tints[slot][index] = tint;
            light[slot][index] = (short) (block | sky << 4 | emission << 8);
            if (kind == SectionSnapshot.OPAQUE_CUBE || kind == SectionSnapshot.MODEL) occupancy.set(x, y, z, 1);
        }

        List<SectionSnapshot> sections(int originSectionX, int originSectionY, int originSectionZ) {
            List<SectionSnapshot> sections = new ArrayList<>(27);
            for (int tz = 0; tz < 3; tz++) for (int ty = 0; ty < 3; ty++) for (int tx = 0; tx < 3; tx++) {
                int slot = tx + 3 * (ty + 3 * tz);
                sections.add(new SectionSnapshot(originSectionX + tx, originSectionY + ty, originSectionZ + tz, 1,
                    cells[slot], new CubeSectionMaterials(1, List.of(CUBE), cubes[slot]),
                    new SectionModelGeometry(1, List.of(WATER, LEAVES), models[slot]),
                    new SectionLightData(light[slot]), new SectionTintData(tints[slot])));
            }
            return sections;
        }
    }
}