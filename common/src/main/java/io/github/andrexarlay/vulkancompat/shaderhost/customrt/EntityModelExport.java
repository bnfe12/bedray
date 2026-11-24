package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import org.joml.Vector3f;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes every entity model layer the game knows (vanilla and mods) as textured quads for Mosaic Studio, which has no
 * other way to see entity models: they are defined in code, not in resource-pack JSON. Format:
 * {@code {"version":1,"layers":[{"id":"minecraft:cow#main","texW":64,"texH":64,"q":[x,y,z,u,v, x4 per quad...]}]}}
 * with positions in blocks (model space, y down as in the game) and u,v in 0..1.
 */
public final class EntityModelExport {

    private EntityModelExport() {}

    /** Exports {@code roots} to {@code out} (written to a temp file, then moved). Returns the number of layers written. */
    public static int export(Map<ModelLayerLocation, LayerDefinition> roots, Path out) throws java.io.IOException {
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        int count = 0;
        Map<String, ModelLayerLocation> sorted = new TreeMap<>();
        for (ModelLayerLocation l : roots.keySet()) sorted.put(l.toString(), l);
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write("{\"version\":1,\"layers\":[");
            for (Map.Entry<String, ModelLayerLocation> e : sorted.entrySet()) {
                LayerDefinition def = roots.get(e.getValue());
                ModelPart root;
                try {
                    root = def.bakeRoot();
                } catch (RuntimeException ex) {
                    continue; // a layer that needs game state to bake
                }
                int[] size = texSize(def);
                StringBuilder q = new StringBuilder();
                Vector3f p = new Vector3f();
                root.visit(new PoseStack(), (pose, path, index, cube) -> {
                    for (ModelPart.Polygon poly : cube.polygons) {
                        if (poly.vertices().length != 4) continue;
                        for (ModelPart.Vertex v : poly.vertices()) {
                            pose.pose().transformPosition(v.worldX(), v.worldY(), v.worldZ(), p);
                            if (!q.isEmpty()) q.append(',');
                            q.append(num(p.x())).append(',').append(num(p.y())).append(',').append(num(p.z())).append(',')
                                .append(num(v.u())).append(',').append(num(v.v()));
                        }
                    }
                });
                if (q.isEmpty()) continue;
                if (count++ > 0) w.write(',');
                w.write("{\"id\":\"" + e.getKey().replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"texW\":" + size[0] + ",\"texH\":" + size[1]
                    + ",\"q\":[" + q + "]}");
            }
            w.write("]}");
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return count;
    }

    private static String num(float f) {
        if (f == Math.rint(f)) return Integer.toString((int) f);
        return String.format(Locale.ROOT, "%.4f", f).replaceAll("0+$", "");
    }

    /** Texture size of a layer definition (64x32 when it cannot be read). */
    static int[] texSize(LayerDefinition def) {
        try {
            for (java.lang.reflect.Field f : LayerDefinition.class.getDeclaredFields()) {
                if (!f.getType().getSimpleName().equals("MaterialDefinition")) continue;
                f.setAccessible(true);
                Object m = f.get(def);
                java.lang.reflect.Field x = m.getClass().getDeclaredField("xTexSize"), y = m.getClass().getDeclaredField("yTexSize");
                x.setAccessible(true);
                y.setAccessible(true);
                return new int[] {x.getInt(m), y.getInt(m)};
            }
        } catch (Exception ignored) {
            // fall through
        }
        return new int[] {64, 32};
    }

    /** The layers of a running game: every root the entity model set knows (vanilla and mods), else the vanilla list. */
    @SuppressWarnings("unchecked")
    public static Map<ModelLayerLocation, LayerDefinition> gameRoots(Object entityModelSet) {
        try {
            for (java.lang.reflect.Field f : entityModelSet.getClass().getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Map<?, ?> m = (Map<?, ?>) f.get(entityModelSet);
                if (m != null && !m.isEmpty() && m.keySet().iterator().next() instanceof ModelLayerLocation)
                    return (Map<ModelLayerLocation, LayerDefinition>) m;
            }
        } catch (Exception ignored) {
            // fall back
        }
        return net.minecraft.client.model.geom.LayerDefinitions.createRoots();
    }
}
