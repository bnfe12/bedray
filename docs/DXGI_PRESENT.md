# DXGI presentation

Minecraft 26.4 Snapshot 5 adds the experimental **Prefer DXGI Present** option to Video Settings. The game's Vulkan backend owns this path: on compatible Windows systems it shares the rendered Vulkan image with a D3D12/DXGI swapchain. Bedray keeps the Vulkan rendering backend and lets Minecraft handle that presentation path.

When using a Bedray build made for a Minecraft version that includes the option, enable it in **Options → Video Settings → Prefer DXGI Present** and restart the game. Minecraft leaves it off by default and only enables it when its device checks pass. The setting may improve IME, variable-refresh-rate, and fullscreen alt-tab behavior; it is experimental.

Bedray's frame-generation providers currently hook or replace the Vulkan `VkSwapchainKHR`. If a provider is selected at startup, Bedray asks Minecraft to keep the Vulkan present path for that session. The saved DXGI preference remains available for a later launch with frame generation off.

The current Bedray release targets Minecraft 26.3. This compatibility hook is optional on older game versions; the DXGI option itself requires a Minecraft build that includes Mojang's 26.4 implementation.
