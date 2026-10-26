package io.github.andrexarlay.vulkanbridge.glbridge;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Hidden OpenGL 4.6 core context on an SDL3 window, made current on the calling (GL bridge) thread. SDL is what the game
 * already uses for its window (lwjgl-sdl 3.4.3; the launcher has no GLFW 3.4.3, and the engine does not use GLFW).
 * SDL signatures: javap on lwjgl-sdl-3.4.3.jar (SDLVideo.SDL_CreateWindow(CharSequence,int,int,long),
 * SDL_GL_SetAttribute(int,int), SDL_GL_CreateContext(long), SDL_GL_MakeCurrent(long,long), SDL_GL_DestroyContext(long)).
 * All methods must run on the GL bridge thread.
 */
final class GlContext {
    long window;
    long context;
    GLCapabilities caps;
    String vendor, renderer, version;
    final List<byte[]> deviceUuids = new ArrayList<>();
    final byte[] driverUuid = new byte[16];
    final byte[] luid = new byte[8];
    int nodeMask;

    static GlContext create() {
        GlContext g = new GlContext();
        sdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 4), "SDL_GL_SetAttribute(major)");
        sdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 6), "SDL_GL_SetAttribute(minor)");
        sdl(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE),
            "SDL_GL_SetAttribute(profile)");
        g.window = SDLVideo.SDL_CreateWindow("VKB GL bridge (hidden)", 64, 64, SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
        if (g.window == 0L) {
            throw new IllegalStateException("SDL_CreateWindow(OPENGL|HIDDEN) failed: " + SDLError.SDL_GetError());
        }
        // From now on the engine's SDL event loop must ignore this window's events (MixinWindowForeignEvents).
        GlBridgeConfig.setBridgeWindowHandle(g.window);
        try {
            g.context = SDLVideo.SDL_GL_CreateContext(g.window);
            if (g.context == 0L) {
                throw new IllegalStateException("SDL_GL_CreateContext(4.6 core) failed: " + SDLError.SDL_GetError());
            }
            sdl(SDLVideo.SDL_GL_MakeCurrent(g.window, g.context), "SDL_GL_MakeCurrent");
            // LWJGL default function provider (opengl32 + wglGetProcAddress): valid for any WGL context current on this thread.
            if (GL.getFunctionProvider() == null) {
                GL.create();
            }
            g.caps = GL.createCapabilities();
            g.vendor = glGetString(GL_VENDOR);
            g.renderer = glGetString(GL_RENDERER);
            g.version = glGetString(GL_VERSION);
        } catch (Throwable t) {
            // The caller keeps nothing but the returned object, so a failure here would leave a hidden 64x64 window alive while
            // shutdown clears the handle (the caller sees glCtx == null) — and an unregistered window has its events forwarded
            // to the real game window, which is the historical "the game became 64x64" bug. Destroy it with the failure.
            try {
                if (g.context != 0L) {
                    SDLVideo.SDL_GL_MakeCurrent(g.window, 0L);
                    SDLVideo.SDL_GL_DestroyContext(g.context);
                    g.context = 0L;
                }
                // destroy() releases the capabilities of this thread with the context; a half-built context must not leave them
                // behind either, or the GL thread would dispatch against a driver object that no longer exists.
                GL.setCapabilities(null);
                SDLVideo.SDL_DestroyWindow(g.window);
                g.window = 0L;
                GlBridgeConfig.setBridgeWindowHandle(0L);
            } catch (Throwable unwind) {
                GlLog.error(5, "result=gl_context_unwind_failed reason=" + unwind, unwind);
            }
            throw t;
        }
        return g;
    }

    /**
     * GL thread. Releases the context from the thread, destroys it and the hidden window (SDL_GL_MakeCurrent(window, 0),
     * SDL_GL_DestroyContext, SDL_DestroyWindow: signatures from javap on lwjgl-sdl-3.4.3.jar) and drops the LWJGL capabilities of this thread.
     * The window is destroyed on the thread that created it.
     */
    void destroy() {
        sdl(SDLVideo.SDL_GL_MakeCurrent(window, 0L), "SDL_GL_MakeCurrent(release)");
        SDLVideo.SDL_GL_DestroyContext(context);
        SDLVideo.SDL_DestroyWindow(window);
        GL.setCapabilities(null);
        context = 0L;
        window = 0L;
    }

    private static void sdl(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException(what + " failed: " + SDLError.SDL_GetError());
        }
    }

    boolean hasInteropExtensions() {
        return caps.GL_EXT_memory_object && caps.GL_EXT_memory_object_win32 && caps.GL_EXT_semaphore && caps.GL_EXT_semaphore_win32;
    }

    /**
     * Tokens/signatures from EXT_external_objects (DEVICE_UUID_EXT 0x9597, DRIVER_UUID_EXT 0x9598, GetUnsignedBytevEXT,
     * GetUnsignedBytei_vEXT) and EXT_external_objects_win32 (DEVICE_LUID_EXT 0x9599 via GetUnsignedBytevEXT,
     * DEVICE_NODE_MASK_EXT 0x959A via GetIntegerv); registry.khronos.org, checked 2026-10-10 (docs/experiments/gl-interop-smoke.md).
     */
    void readIdentity() {
        int numUuids = glGetInteger(EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT);
        for (int i = 0; i < numUuids; i++) {
            ByteBuffer b = BufferUtils.createByteBuffer(EXTMemoryObject.GL_UUID_SIZE_EXT);
            EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, i, b);
            byte[] u = new byte[16];
            b.get(u);
            deviceUuids.add(u);
        }
        ByteBuffer d = BufferUtils.createByteBuffer(EXTMemoryObject.GL_UUID_SIZE_EXT);
        EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObject.GL_DRIVER_UUID_EXT, d);
        d.get(driverUuid);
        ByteBuffer l = BufferUtils.createByteBuffer(EXTMemoryObjectWin32.GL_LUID_SIZE_EXT);
        EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT, l);
        l.get(luid);
        IntBuffer nm = BufferUtils.createIntBuffer(1);
        glGetIntegerv(EXTMemoryObjectWin32.GL_DEVICE_NODE_MASK_EXT, nm);
        nodeMask = nm.get(0);
    }
}
