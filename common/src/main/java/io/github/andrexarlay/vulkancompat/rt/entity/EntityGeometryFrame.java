package io.github.andrexarlay.vulkancompat.rt.entity;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Common contract representing captured entity geometry for a single frame.
 */
public record EntityGeometryFrame(
    long frameId,
    List<CapturedEntity> entities,
    int totalVertices,
    int totalIndices,
    long geometryGeneration,
    float[] vertexData,
    int[] indexData,
    float[] uvData,
    int[] colorData,
    int[] lightData
) {
    public EntityGeometryFrame {
        entities = entities != null ? List.copyOf(entities) : List.of();
    }

    public EntityGeometryFrame(long frameId, List<CapturedEntity> entities, int totalVertices, int totalIndices, long geometryGeneration) {
        this(frameId, entities, totalVertices, totalIndices, geometryGeneration, null, null, null, null, null);
    }

    public EntityGeometryFrame(long frameId, List<CapturedEntity> entities, int totalVertices, int totalIndices, long geometryGeneration,
                               float[] vertexData, int[] indexData) {
        this(frameId, entities, totalVertices, totalIndices, geometryGeneration, vertexData, indexData, null, null, null);
    }

    public static final EntityGeometryFrame EMPTY = new EntityGeometryFrame(0L, List.of(), 0, 0, 0L, null, null, null, null, null);

    public boolean hasEntities() {
        return (entities != null && !entities.isEmpty()) || totalVertices > 0;
    }

    public record CapturedEntity(
        int entityId,
        String entityType,
        Matrix4f currentTransform,
        Matrix4f previousTransform,
        int vertexOffset,
        int vertexCount,
        int indexOffset,
        int indexCount,
        int materialId,
        int renderFlags,
        boolean isTranslucent
    ) {
        public static final int FLAG_LIVING = 1 << 0;       // 0x01
        public static final int FLAG_ITEM = 1 << 1;         // 0x02
        public static final int FLAG_BLOCK_ENTITY = 1 << 2; // 0x04
        public static final int FLAG_TRANSLUCENT = 1 << 3;  // 0x08
        public static final int FLAG_GLOWING = 1 << 4;      // 0x10
        public static final int FLAG_FIRE = 1 << 5;         // 0x20
        public static final int FLAG_CROUCHING = 1 << 6;     // 0x40
        public static final int FLAG_OUTLINE = 1 << 7;      // 0x80

        public boolean isLiving() { return (renderFlags & FLAG_LIVING) != 0; }
        public boolean isItem() { return (renderFlags & FLAG_ITEM) != 0; }
        public boolean isBlockEntity() { return (renderFlags & FLAG_BLOCK_ENTITY) != 0; }
    }

    public static Builder builder(long frameId, long geometryGeneration) {
        return new Builder(frameId, geometryGeneration);
    }

    public static class Builder {
        private final long frameId;
        private final long generation;
        private final List<CapturedEntity> entities = new ArrayList<>();
        private int totalVertices = 0;
        private int totalIndices = 0;
        private float[] vertexData;
        private int[] indexData;
        private float[] uvData;
        private int[] colorData;
        private int[] lightData;

        public Builder(long frameId, long generation) {
            this.frameId = frameId;
            this.generation = generation;
        }

        public Builder addEntity(CapturedEntity entity) {
            entities.add(entity);
            totalVertices += entity.vertexCount();
            totalIndices += entity.indexCount();
            return this;
        }

        public Builder totalVertices(int totalVertices) {
            this.totalVertices = totalVertices;
            return this;
        }

        public Builder totalIndices(int totalIndices) {
            this.totalIndices = totalIndices;
            return this;
        }

        public Builder vertexData(float[] vertexData) {
            this.vertexData = vertexData;
            return this;
        }

        public Builder indexData(int[] indexData) {
            this.indexData = indexData;
            return this;
        }

        public Builder uvData(float[] uvData) {
            this.uvData = uvData;
            return this;
        }

        public Builder colorData(int[] colorData) {
            this.colorData = colorData;
            return this;
        }

        public Builder lightData(int[] lightData) {
            this.lightData = lightData;
            return this;
        }

        public EntityGeometryFrame build() {
            return new EntityGeometryFrame(frameId, entities, totalVertices, totalIndices, generation,
                vertexData, indexData, uvData, colorData, lightData);
        }
    }
}
