"""Keep the Minecraft window genuinely focused during an exclusive bench capture.

The strict native-1080p acceptance gate (capture_analysis.NATIVE_FLAGS) requires the FOCUSED bit (64)
for every recorded frame; FrameBenchmark sets it from Minecraft.getWindow().isFocused(). On a contended
desktop another window steals focus after settle, the bit drops, and a rendering-valid capture is rejected
— an artifact of the desktop, not a defect of the feature under test (docs/measurements/GI_STACK_REPORT.md,
"Honest limitations"). This helper holds the real window focus so the real flag holds; it never writes or
fakes the flag.

Only used when the bench owns the desktop: bench_gi_stack refuses to start while another javaw.exe runs, so
re-focusing our own window cannot interfere with another session's game. Windows-only; a no-op elsewhere.
Opt-in via bench_gi_stack --hold-focus.
"""
from __future__ import annotations

import sys
import threading
import time


class FocusHold:
    """Daemon thread that re-focuses the first visible window whose title contains ``title_hint``."""

    def __init__(self, title_hint: str = "Minecraft", interval_s: float = 1.0):
        self.title_hint = title_hint.lower()
        self.interval_s = interval_s
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.refocus_count = 0

    def __enter__(self) -> "FocusHold":
        if sys.platform == "win32":
            self._thread = threading.Thread(target=self._run, name="focus-hold", daemon=True)
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
        EnumWindows = user32.EnumWindows
        GetWindowTextW = user32.GetWindowTextW
        GetWindowTextLengthW = user32.GetWindowTextLengthW
        IsWindowVisible = user32.IsWindowVisible
        SetForegroundWindow = user32.SetForegroundWindow
        BringWindowToTop = user32.BringWindowToTop
        GetForegroundWindow = user32.GetForegroundWindow
        GetWindowThreadProcessId = user32.GetWindowThreadProcessId
        AttachThreadInput = user32.AttachThreadInput
        ShowWindow = user32.ShowWindow
        GetCurrentThreadId = ctypes.windll.kernel32.GetCurrentThreadId
        SW_RESTORE = 9

        WNDENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        def find_window() -> int | None:
            found: list[int] = []

            def cb(hwnd, _lparam):
                if IsWindowVisible(hwnd):
                    length = GetWindowTextLengthW(hwnd)
                    if length > 0:
                        buf = ctypes.create_unicode_buffer(length + 1)
                        GetWindowTextW(hwnd, buf, length + 1)
                        if self.title_hint in buf.value.lower():
                            found.append(int(hwnd))
                            return False
                return True

            EnumWindows(WNDENUMPROC(cb), 0)
            return found[0] if found else None

        def force_foreground(hwnd: int) -> bool:
            # Windows blocks SetForegroundWindow from a background process; attach to the foreground
            # thread's input queue first so the call is allowed, then detach.
            fore = GetForegroundWindow()
            fore_tid = GetWindowThreadProcessId(fore, None) if fore else 0
            target_tid = GetWindowThreadProcessId(hwnd, None)
            current_tid = GetCurrentThreadId()
            attached = False
            if fore_tid and fore_tid != current_tid:
                attached = bool(AttachThreadInput(current_tid, fore_tid, True))
            ShowWindow(hwnd, SW_RESTORE)
            ok = bool(SetForegroundWindow(hwnd))
            BringWindowToTop(hwnd)
            if attached:
                AttachThreadInput(current_tid, fore_tid, False)
            return ok

        while not self._stop.is_set():
            hwnd = find_window()
            if hwnd is not None and GetForegroundWindow() != hwnd:
                if force_foreground(hwnd):
                    self.refocus_count += 1
            self._stop.wait(self.interval_s)


if __name__ == "__main__":
    # Manual smoke test: python focus_hold.py "Minecraft"  (Ctrl+C to stop). No game launch.
    hint = sys.argv[1] if len(sys.argv) > 1 else "Minecraft"
    with FocusHold(hint) as hold:
        print(f"holding focus for title hint {hint!r}; Ctrl+C to stop", flush=True)
        try:
            while True:
                time.sleep(5)
                print(f"refocus_count={hold.refocus_count}", flush=True)
        except KeyboardInterrupt:
            pass