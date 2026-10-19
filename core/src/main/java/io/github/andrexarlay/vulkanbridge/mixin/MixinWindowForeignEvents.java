package io.github.andrexarlay.vulkanbridge.mixin;

import io.github.andrexarlay.vulkanbridge.glbridge.GlBridgeConfig;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the GL core's hidden SDL window from resizing the game. Root cause (found in the game, run of 2026-10-10 23:57:
 * autotest reported window=64x64 renderTarget=64x64 right after the hidden 64x64 GL window was created):
 * {@code SDLEventHandler.pollEvents()} passes every SDL event to {@code Window.handleEvent(SDL_Event)}, and for
 * SDL_EVENT_WINDOW_RESIZED (518) / PIXEL_SIZE_CHANGED (519) / MOVED (517) / FOCUS etc. that method never compares the event's
 * window with its own handle, so the events of a second SDL window are applied to the game window.
 * This injection drops window events that belong to the core window (and only those).
 * Signatures: javap -p on libs/minecraft-26.3-client.jar: {@code public void handleEvent(org.lwjgl.sdl.SDL_Event)};
 * lwjgl-sdl-3.4.3: {@code SDLEvents.SDL_GetWindowFromEvent(SDL_Event)J}, SDL_EVENT_WINDOW_FIRST=514, SDL_EVENT_WINDOW_LAST=538.
 */
@Mixin(targets = "com.mojang.blaze3d.platform.Window", remap = false)
public abstract class MixinWindowForeignEvents {
    @Inject(method = "handleEvent(Lorg/lwjgl/sdl/SDL_Event;)V", at = @At("HEAD"), cancellable = true)
    private void vkb$dropBridgeWindowEvents(SDL_Event event, CallbackInfo ci) {
        long bridgeWindow = GlBridgeConfig.bridgeWindowHandle();
        if (bridgeWindow == 0L) {
            return;
        }
        int type = event.type();
        if (type >= SDLEvents.SDL_EVENT_WINDOW_FIRST && type <= SDLEvents.SDL_EVENT_WINDOW_LAST
            && SDLEvents.SDL_GetWindowFromEvent(event) == bridgeWindow) {
            ci.cancel();
        }
    }
}
