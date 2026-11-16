"""Keep the Minecraft window behind every other window while a bench capture runs.

The owner keeps using the desktop during a bench, so the game window must never sit on top of their work.
`FocusHold` does the opposite (it forces the window to the foreground for a strict focused capture); this helper
pushes the window to the bottom of the z-order without activating it, once a second, so the launcher's initial
raise and any later re-raise are undone. The window stays visible-and-restored (not minimized, not off-screen):
vanilla throttles an iconified window to 10 FPS, and GPU timestamps do not depend on the z-order.

A capture taken this way must run with ``-Dvkrtx.autotest.allowUnfocused=true`` (set by ``bench_gi_stack
--background``), which makes the capture record the waiver as the manifest fact ``focus_waived=true``.
Windows only; a no-op elsewhere.
"""
from __future__ import annotations

import sys
import threading


class BackgroundHold:
    """Daemon thread that sends the first visible window whose title contains ``title_hint`` to the bottom."""

    def __init__(self, title_hint: str = "Minecraft", interval_s: float = 1.0):
        self.title_hint = title_hint.lower()
        self.interval_s = interval_s
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.pushed_count = 0

    def __enter__(self) -> "BackgroundHold":
        if sys.platform == "win32":
            self._thread = threading.Thread(target=self._run, name="background-hold", daemon=True)
            self._thread.start()
        return self

    def __exit__(self, *exc) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=2.0)

    def _run(self) -> None:
        import ctypes
        from ctypes import wintypes

        user32 = ctypes.windll.user32
        user32.GetWindow.restype = wintypes.HWND
        HWND_BOTTOM = wintypes.HWND(1)
        SWP_NOSIZE, SWP_NOMOVE, SWP_NOACTIVATE = 0x0001, 0x0002, 0x0010
        GW_HWNDNEXT = 2
        WNDENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        def find_window() -> int | None:
            found: list[int] = []

            def cb(hwnd, _lparam):
                if user32.IsWindowVisible(hwnd):
                    length = user32.GetWindowTextLengthW(hwnd)
                    if length > 0:
                        buf = ctypes.create_unicode_buffer(length + 1)
                        user32.GetWindowTextW(hwnd, buf, length + 1)
                        if self.title_hint in buf.value.lower():
                            found.append(int(hwnd))
                            return False
                return True

            user32.EnumWindows(WNDENUMPROC(cb), 0)
            return found[0] if found else None

        def is_bottom(hwnd: int) -> bool:
            # Bottom of the z-order: no visible top-level window follows it.
            nxt = user32.GetWindow(hwnd, GW_HWNDNEXT)
            while nxt:
                if user32.IsWindowVisible(nxt):
                    return False
                nxt = user32.GetWindow(nxt, GW_HWNDNEXT)
            return True

        while not self._stop.is_set():
            hwnd = find_window()
            if hwnd is not None and not is_bottom(hwnd):
                if user32.SetWindowPos(hwnd, HWND_BOTTOM, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE):
                    self.pushed_count += 1
            self._stop.wait(self.interval_s)


if __name__ == "__main__":
    hint = sys.argv[1] if len(sys.argv) > 1 else "Minecraft"
    with BackgroundHold(hint) as hold:
        print(f"keeping window {hint!r} at the bottom; Ctrl+C to stop", flush=True)
        try:
            while True:
                threading.Event().wait(5)
                print(f"pushed_count={hold.pushed_count}", flush=True)
        except KeyboardInterrupt:
            pass
