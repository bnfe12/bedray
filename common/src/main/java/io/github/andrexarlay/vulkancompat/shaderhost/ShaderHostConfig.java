package io.github.andrexarlay.vulkancompat.shaderhost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The {@code shader_host} key of {@code config/bedray.toml} (native | iris). VulkanRtxConfig delegates the key on load and
 * writes it on save; the early bootstrap reads it directly with {@link #readFrom} so that VulkanRtxConfig (whose static load
 * also validates upscaler libraries) is not initialised earlier than before. {@code -Dvkrtx.shader_host} overrides it for one
 * session and is never saved.
 */
@Experimental("I0")
public final class ShaderHostConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/ShaderHost");
    public static final String KEY = "shader_host";
    public static final String OVERRIDE_PROPERTY = "vkrtx.shader_host";

    /** Persisted value. Default native: without an explicit choice nothing changes for existing users. */
    private static volatile ShaderHostKind persisted = ShaderHostKind.NATIVE;

    private ShaderHostConfig() {}

    public static ShaderHostKind persisted() {
        return persisted;
    }

    public static void setPersisted(ShaderHostKind kind) {
        persisted = kind == null ? ShaderHostKind.NATIVE : kind;
    }

    /** Effective request of this session: the -D override when valid, else the persisted value. */
    public static ShaderHostKind requested() {
        String o = System.getProperty(OVERRIDE_PROPERTY);
        if (o != null && !o.isBlank()) {
            ShaderHostKind k = ShaderHostKind.parse(o);
            if (k != null) {
                return k;
            }
            LOGGER.error("[VKB-SHADERHOST] invalid -D{}={} (use native|iris), using {}", OVERRIDE_PROPERTY, o, persisted.configName());
        }
        return persisted;
    }

    /** Called by VulkanRtxConfig.applyKeyValue for {@code shader_host}; an unknown value is logged and native is kept. */
    public static void applyValue(String value) {
        ShaderHostKind k = ShaderHostKind.parse(value);
        if (k == null) {
            LOGGER.warn("[VKB-SHADERHOST] unknown {} '{}' (use native|iris), using native", KEY, value);
            k = ShaderHostKind.NATIVE;
        }
        persisted = k;
    }

    /** Appends the section to bedray.toml (called by VulkanRtxConfig.save). */
    public static void write(Writer w) throws IOException {
        w.write("\n[shader_host]\n");
        w.write("# [EXPERIMENTAL] native = built-in ray/path tracing renderer. iris = Iris/OptiFine shaderpacks on Vulkan: in construction,\n");
        w.write("# it only reads the pack chosen in config/iris.properties and falls back to native (no pack is rendered yet)\n");
        w.write(KEY + " = \"" + persisted.configName() + "\"\n");
    }

    /** Reads only {@code shader_host} from a toml file (absent file/key: native). Same tolerant syntax as VulkanRtxConfig.load. */
    public static void readFrom(Path toml) {
        if (!Files.isRegularFile(toml, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(toml, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.startsWith("﻿")) {
                    line = line.substring(1).strip();
                }
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq < 0) {
                    continue;
                }
                if (line.substring(0, eq).strip().equals(KEY)) {
                    applyValue(line.substring(eq + 1));
                }
            }
        } catch (IOException e) {
            LOGGER.error("[VKB-SHADERHOST] could not read {}: {}", toml, e.toString());
        }
    }
}
