package io.github.andrexarlay.vulkancompat.shaderhost;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns the configured host into the active one. Rules:
 * <ol>
 *   <li>a host disabled for this session reports {@link ShaderHostState#DISABLED_FOR_SESSION} without being probed again;</li>
 *   <li>the requested host is activated only when its probe is {@link ShaderHostState#READY};</li>
 *   <li>otherwise native is activated (fallback); native itself has no fallback.</li>
 * </ol>
 */
@Experimental("I0")
public final class ShaderHostSelector {

    /**
     * @param requested       host from config ({@code shader_host})
     * @param requestedStatus probe result of the requested host
     * @param active          host that renders (requested, or NATIVE on fallback)
     * @param activeStatus    probe result of the active host
     */
    public record Selection(ShaderHostKind requested, ShaderHostStatus requestedStatus,
                            ShaderHostKind active, ShaderHostStatus activeStatus) {
        public boolean fellBack() {
            return requested != active;
        }

        /** One log line; begins with {@code [VKB-SHADERHOST] host=<active> state=<state>} (grep key of the verifier). */
        public String logLine() {
            StringBuilder sb = new StringBuilder("[VKB-SHADERHOST] host=").append(active.configName())
                .append(" state=").append(activeStatus.state())
                .append(" requested=").append(requested.configName());
            if (fellBack()) {
                sb.append(" requested_state=").append(requestedStatus.state())
                    .append(" fallback=true reason=\"").append(requestedStatus.detail()).append('"');
            }
            sb.append(" detail=\"").append(activeStatus.detail()).append('"');
            return sb.toString();
        }
    }

    private final Map<ShaderHostKind, ShaderHost> hosts = new EnumMap<>(ShaderHostKind.class);
    private final Map<ShaderHostKind, String> disabled = new EnumMap<>(ShaderHostKind.class);

    public ShaderHostSelector(ShaderHost nativeHost, ShaderHost irisHost) {
        register(Objects.requireNonNull(nativeHost, "nativeHost"), ShaderHostKind.NATIVE);
        register(Objects.requireNonNull(irisHost, "irisHost"), ShaderHostKind.IRIS);
    }

    private void register(ShaderHost host, ShaderHostKind expected) {
        if (host.kind() != expected) {
            throw new IllegalArgumentException("host " + host.kind() + " registered as " + expected);
        }
        hosts.put(expected, host);
    }

    public ShaderHost host(ShaderHostKind kind) {
        return hosts.get(kind);
    }

    /** Runtime failure of a host (e.g. pack compile error in I2): stays off until restart. Native cannot be disabled. */
    public void disableForSession(ShaderHostKind kind, String reason) {
        if (kind == ShaderHostKind.NATIVE) {
            throw new IllegalArgumentException("the native host is the fallback and cannot be disabled");
        }
        disabled.put(kind, reason == null ? "" : reason);
    }

    public boolean isDisabledForSession(ShaderHostKind kind) {
        return disabled.containsKey(kind);
    }

    public ShaderHostStatus probe(ShaderHostKind kind, ShaderHostContext ctx) {
        String reason = disabled.get(kind);
        if (reason != null) {
            return ShaderHostStatus.of(kind, ShaderHostState.DISABLED_FOR_SESSION, reason);
        }
        ShaderHostStatus st = hosts.get(kind).probe(ctx);
        if (st.host() != kind) {
            throw new IllegalStateException("host " + kind + " answered for " + st.host());
        }
        return st;
    }

    public Selection select(ShaderHostKind requested, ShaderHostContext ctx) {
        Objects.requireNonNull(requested, "requested");
        ShaderHostStatus req = probe(requested, ctx);
        if (req.state().isUsable() || requested == ShaderHostKind.NATIVE) {
            return new Selection(requested, req, requested, req);
        }
        ShaderHostStatus nat = probe(ShaderHostKind.NATIVE, ctx);
        return new Selection(requested, req, ShaderHostKind.NATIVE, nat);
    }

    /** Invalidation for a switch from {@code from} to the (already probed) {@code to}. */
    public HostInvalidation.Plan planSwitch(ShaderHostKind from, ShaderHostKind to, ShaderHostContext ctx) {
        Set<String> extra = hosts.get(to).extraDeviceExtensions();
        return HostInvalidation.plan(from, to, extra, ctx.deviceExtensions());
    }
}
