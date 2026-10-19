package io.github.andrexarlay.vulkancompat.neoforge;

import io.github.andrexarlay.vulkanbridge.neoforge.NeoForgeAccess;
import io.github.andrexarlay.vulkanbridge.platform.BridgeBootstrap;
import io.github.andrexarlay.vulkancompat.RtxBootstrap;
import io.github.andrexarlay.vulkancompat.rt.keybind.VulkanRtxKeyBinds;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge entry point of the Bedray mod, the single {@code @Mod} of the jar. It first installs the loader seam of the bundled
 * Bedray Core ({@link NeoForgeAccess}, starts the autotest harness only when -Dvkbridge.autotest=&lt;scenario&gt; is set), then
 * makes one call into the loader-neutral {@link RtxBootstrap}. Order matters: the Core seam must exist before the Bedray
 * providers register. Constructor signature (IEventBus, ModContainer): FML loader-12.0.9 {@code javap -c FMLModContainer}.
 */
@Mod(RtxBootstrap.MOD_ID)
public final class VulkanRtxMod {
    public VulkanRtxMod(IEventBus modBus, ModContainer container) {
        BridgeBootstrap.earlyInit(new NeoForgeAccess());
        BridgeBootstrap.clientInit();
        RtxBootstrap.earlyInit();
        // /bedray report: client-side command (RegisterClientCommandsEvent, javap neoforge-26.3.0.57-beta-universal.jar); the same report as
        // the "Copy diagnostics report" button. Fabric has no command API without Fabric API, it uses the button.
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event -> event.getDispatcher().register(
            net.minecraft.commands.Commands.literal("bedray").then(net.minecraft.commands.Commands.literal("report").executes(ctx -> {
                io.github.andrexarlay.vulkancompat.rtx.debug.Diagnostics.generateAndNotify();
                return 1;
            }))));
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.registerCategory(VulkanRtxKeyBinds.CATEGORY);
            for (KeyMapping key : VulkanRtxKeyBinds.ALL_KEYS) {
                event.register(key);
            }
        });
        container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
            (mc, parent) -> new io.github.andrexarlay.vulkancompat.sodium.BedrayConfigScreen(parent));
    }
}
