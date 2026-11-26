"""Szemkímélő – 20-20-20 emlékeztető Windowsra.

Szabály: 20 percenként nézz 20 másodpercig kb. 6 méterre (20 láb) lévő dolgot.
Az app a tálcán fut, és a beállított időnként szünet-ablakot jelenít meg.
"""
from __future__ import annotations

import ctypes
import json
import math
import os
import queue
import sys
import time
import tkinter as tk
from tkinter import ttk

import pystray
from PIL import Image, ImageDraw

try:
    import winreg
except ImportError:  # nem Windows – csak a fejlesztéshez
    winreg = None

APP_NAME = "Szemkímélő"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE = "Szemkimelo"
CONFIG_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "Szemkimelo")
CONFIG_PATH = os.path.join(CONFIG_DIR, "config.json")

DEFAULTS = {
    "work_minutes": 20,
    "break_seconds": 20,
    "fullscreen": True,
    "autostart": False,
}

# Színek
OVERLAY_BG = "#050a14"
BG = "#14213d"
FG = "#ffffff"
ACCENT = "#4cc9f0"
MUTED = "#a9b4c9"


# ---------------------------------------------------------------- konfiguráció
def load_config() -> dict:
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg.update(json.load(f))
    except (OSError, ValueError):
        pass
    return cfg


def save_config(cfg: dict) -> None:
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)


# ---------------------------------------------------------------- Windows segéd
SHOW_EVENT_NAME = "Local\\SzemkimeloShow"


def single_instance() -> bool:
    """False, ha az app már fut; ilyenkor jelezzük a futó példánynak, hogy mutassa magát."""
    k32 = ctypes.windll.kernel32
    k32.CreateMutexW(None, False, "Local\\SzemkimeloApp")
    if k32.GetLastError() != 183:  # ERROR_ALREADY_EXISTS
        return True
    handle = k32.OpenEventW(0x0002, False, SHOW_EVENT_NAME)  # EVENT_MODIFY_STATE
    if handle:
        k32.SetEvent(handle)
        k32.CloseHandle(handle)
    return False


def watch_show_event(callback) -> None:
    """Háttérszál: minden újraindítási kísérletnél meghívja a callbacket."""
    import threading

    k32 = ctypes.windll.kernel32
    handle = k32.CreateEventW(None, False, False, SHOW_EVENT_NAME)

    def loop() -> None:
        while True:
            if k32.WaitForSingleObject(handle, 0xFFFFFFFF) == 0:
                callback()

    threading.Thread(target=loop, daemon=True).start()


def idle_seconds() -> float:
    class LASTINPUTINFO(ctypes.Structure):
        _fields_ = [("cbSize", ctypes.c_uint), ("dwTime", ctypes.c_uint)]

    info = LASTINPUTINFO()
    info.cbSize = ctypes.sizeof(info)
    if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(info)):
        return 0.0
    millis = ctypes.windll.kernel32.GetTickCount() - info.dwTime
    return max(0, millis) / 1000.0


def autostart_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}" --background'
    pythonw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    if not os.path.exists(pythonw):
        pythonw = sys.executable
    return f'"{pythonw}" "{os.path.abspath(__file__)}" --background'


def set_autostart(enabled: bool) -> None:
    if winreg is None:
        return
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            winreg.SetValueEx(key, RUN_VALUE, 0, winreg.REG_SZ, autostart_command())
        else:
            try:
                winreg.DeleteValue(key, RUN_VALUE)
            except FileNotFoundError:
                pass


def blend(c1: str, c2: str, k: float) -> str:
    """Két #rrggbb szín keverése (k=0 -> c1, k=1 -> c2)."""
    a = [int(c1[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(c2[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * k):02x}" for x, y in zip(a, b))


# ---------------------------------------------------------------- tálca ikon
def make_icon(color: str = "#4cc9f0") -> Image.Image:
    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((2, 14, 62, 50), fill="#ffffff", outline="#14213d", width=3)  # szem
    d.ellipse((20, 16, 44, 48), fill=color, outline="#14213d", width=2)  # írisz
    d.ellipse((26, 24, 38, 40), fill="#14213d")  # pupilla
    d.ellipse((30, 26, 34, 30), fill="#ffffff")  # fény
    return img


def fmt(seconds: float) -> str:
    seconds = max(0, int(seconds + 0.999))
    m, s = divmod(seconds, 60)
    return f"{m}:{s:02d}"


# ---------------------------------------------------------------- az alkalmazás
class App:
    def __init__(self, background: bool = False) -> None:
        self.cfg = load_config()
        self.main_win: tk.Toplevel | None = None
        self.events: queue.Queue[str] = queue.Queue()

        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title(APP_NAME)

        self.state = "working"  # working | paused | break
        self.next_break = time.monotonic() + self.work_secs
        self.paused_remaining = 0.0
        self.break_end = 0.0
        self.break_win: tk.Toplevel | None = None
        self.overlay_win: tk.Toplevel | None = None
        self.settings_win: tk.Toplevel | None = None

        self.icon = pystray.Icon(
            "szemkimelo", make_icon(), APP_NAME, menu=self.build_menu()
        )
        self.icon.run_detached()
        watch_show_event(lambda: self.events.put("show"))

        if not background:
            self.show_main()
        else:
            self.notify("Az app a tálcán fut. Kattints az ikonra a megnyitáshoz.")

        self.root.after(250, self.tick)

    def notify(self, text: str) -> None:
        try:
            self.icon.notify(text, APP_NAME)
        except Exception:
            pass

    # ---- főablak
    def show_main(self) -> None:
        if self.main_win is not None:
            self.main_win.deiconify()
            self.main_win.lift()
            self.main_win.attributes("-topmost", True)
            self.main_win.after(300, lambda: self.main_win and self.main_win.attributes("-topmost", False))
            self.main_win.focus_force()
            return

        win = tk.Toplevel(self.root)
        self.main_win = win
        win.title(APP_NAME)
        win.resizable(False, False)
        win.configure(bg=BG)
        w, h = 400, 330
        sw, sh = win.winfo_screenwidth(), win.winfo_screenheight()
        win.geometry(f"{w}x{h}+{(sw - w) // 2}+{(sh - h) // 2}")
        win.attributes("-topmost", True)
        win.after(300, lambda: win.attributes("-topmost", False))

        tk.Label(win, text="👀  Szemkímélő", font=("Segoe UI", 20, "bold"),
                 bg=BG, fg=FG).pack(pady=(22, 2))
        tk.Label(win, text="20-20-20 szabály", font=("Segoe UI", 10),
                 bg=BG, fg=MUTED).pack()
        tk.Label(win, text="Következő szünet:", font=("Segoe UI", 11),
                 bg=BG, fg=MUTED).pack(pady=(16, 0))
        self.main_time = tk.Label(win, text="", font=("Segoe UI", 38, "bold"),
                                  bg=BG, fg=ACCENT)
        self.main_time.pack()

        def btn(text, cmd, parent):
            b = tk.Button(parent, text=text, command=cmd, font=("Segoe UI", 10), bg="#22345c",
                          fg=FG, activebackground="#2c4378", activeforeground=FG,
                          relief="flat", padx=12, pady=5)
            b.pack(side="left", padx=4)
            return b

        row1 = tk.Frame(win, bg=BG)
        row1.pack(pady=(14, 4))
        btn("Szünet most", lambda: self.events.put("break_now"), row1)
        self.pause_btn = btn("Szüneteltetés", lambda: self.events.put("toggle_pause"), row1)
        row2 = tk.Frame(win, bg=BG)
        row2.pack(pady=4)
        btn("Beállítások", lambda: self.events.put("settings"), row2)
        btn("Elrejtés a tálcára", self.hide_main, row2)
        btn("Kilépés", lambda: self.events.put("quit"), row2)

        tk.Label(win, text="Bezárás (X) = a tálcán fut tovább.", font=("Segoe UI", 9),
                 bg=BG, fg=MUTED).pack(pady=(10, 0))
        win.protocol("WM_DELETE_WINDOW", self.hide_main)
        self.update_main()

    def hide_main(self) -> None:
        if self.main_win is not None:
            self.main_win.withdraw()

    def update_main(self) -> None:
        if self.main_win is None:
            return
        if self.state == "break":
            self.main_time.config(text="szünet…")
        else:
            self.main_time.config(text=fmt(self.paused_remaining if self.state == "paused"
                                           else self.next_break - time.monotonic()))
        self.pause_btn.config(text="Folytatás" if self.state == "paused" else "Szüneteltetés")

    # ---- beállítások
    @property
    def work_secs(self) -> int:
        return int(self.cfg["work_minutes"]) * 60

    @property
    def break_secs(self) -> int:
        return int(self.cfg["break_seconds"])

    # ---- tálca menü (a pystray saját szálán fut, ezért csak sorba teszünk)
    def build_menu(self) -> pystray.Menu:
        return pystray.Menu(
            pystray.MenuItem(
                lambda item: f"Következő szünet: {self.remaining_text()}",
                None,
                enabled=False,
            ),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Megnyitás", lambda: self.events.put("show"), default=True),
            pystray.MenuItem("Szünet most", lambda: self.events.put("break_now")),
            pystray.MenuItem(
                lambda item: "Folytatás" if self.state == "paused" else "Szüneteltetés",
                lambda: self.events.put("toggle_pause"),
            ),
            pystray.MenuItem("Időzítő újraindítása", lambda: self.events.put("reset")),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Beállítások…", lambda: self.events.put("settings")),
            pystray.MenuItem("Kilépés", lambda: self.events.put("quit")),
        )

    def remaining_text(self) -> str:
        if self.state == "paused":
            return f"{fmt(self.paused_remaining)} (szüneteltetve)"
        if self.state == "break":
            return "szünet folyamatban"
        return fmt(self.next_break - time.monotonic())

    # ---- főciklus
    def tick(self) -> None:
        try:
            while True:
                self.handle(self.events.get_nowait())
        except queue.Empty:
            pass

        now = time.monotonic()
        if self.state == "working":
            # Ha a felhasználó már eleget pihent (nem érintette a gépet), nullázunk.
            if idle_seconds() >= self.break_secs:
                self.next_break = now + self.work_secs
            elif now >= self.next_break:
                self.start_break()
        elif self.state == "break":
            self.update_break(now)

        self.icon.title = f"{APP_NAME} – {self.remaining_text()}"
        self.update_main()
        self.root.after(80 if self.state == "break" else 250, self.tick)

    def handle(self, event: str) -> None:
        if event == "show":
            self.show_main()
        elif event == "break_now":
            if self.state != "break":
                self.start_break()
        elif event == "toggle_pause":
            self.toggle_pause()
        elif event == "reset":
            if self.state == "break":
                self.end_break()
            else:
                self.state = "working"
                self.next_break = time.monotonic() + self.work_secs
        elif event == "settings":
            self.open_settings()
        elif event == "quit":
            self.quit()

    def toggle_pause(self) -> None:
        if self.state == "paused":
            self.state = "working"
            self.next_break = time.monotonic() + self.paused_remaining
        elif self.state == "working":
            self.paused_remaining = self.next_break - time.monotonic()
            self.state = "paused"

    # ---- szünet ablak
    def start_break(self) -> None:
        self.state = "break"
        self.break_end = time.monotonic() + self.break_secs

        sw, sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()

        # 1) Sötétítő réteg az összes monitoron (áttetsző).
        self.overlay_win = None
        if self.cfg["fullscreen"]:
            u32 = ctypes.windll.user32
            vx, vy = u32.GetSystemMetrics(76), u32.GetSystemMetrics(77)
            vw, vh = u32.GetSystemMetrics(78), u32.GetSystemMetrics(79)
            ov = tk.Toplevel(self.root)
            ov.overrideredirect(True)
            ov.attributes("-topmost", True)
            ov.attributes("-alpha", 0.85)
            ov.configure(bg=OVERLAY_BG)
            ov.geometry(f"{vw}x{vh}{vx:+d}{vy:+d}")
            ov.bind("<Escape>", lambda e: self.end_break())
            self.overlay_win = ov

        # 2) Átlátszatlan kártya az elsődleges monitor közepén; a keret pulzál.
        win = tk.Toplevel(self.root)
        self.break_win = win
        win.overrideredirect(True)
        win.withdraw()  # amíg nincs a helyén, ne villanjon fel a bal felső sarokban
        win.attributes("-topmost", True)
        win.configure(bg=ACCENT)
        self.card = tk.Frame(win, bg=BG)
        self.card.pack(padx=5, pady=5)
        card = self.card

        tk.Label(card, text="👀", font=("Segoe UI Emoji", 48), bg=BG, fg=FG).pack(
            padx=60, pady=(26, 0))
        tk.Label(card, text="Letelt a 20 perc!", font=("Segoe UI", 34, "bold"),
                 bg=BG, fg=FG).pack(pady=(8, 6), padx=40)
        tk.Label(card, text="Nézz egy ~6 méterre lévő dologra,\nés pislogj néhányat.",
                 font=("Segoe UI", 18), bg=BG, fg=MUTED, justify="center").pack()

        self.count_label = tk.Label(card, text=str(self.break_secs),
                                    font=("Segoe UI", 96, "bold"), bg=BG, fg=ACCENT)
        self.count_label.pack(pady=(10, 4))

        style = ttk.Style(win)
        style.theme_use("clam")
        style.configure("Eye.Horizontal.TProgressbar", troughcolor="#22345c",
                        background=ACCENT, bordercolor=BG, lightcolor=ACCENT, darkcolor=ACCENT)
        self.progress = ttk.Progressbar(card, style="Eye.Horizontal.TProgressbar",
                                        length=400, maximum=self.break_secs)
        self.progress.pack(pady=(0, 18), padx=40)

        skip = tk.Button(card, text="Kihagyom (Esc)", font=("Segoe UI", 11), bg="#22345c", fg=FG,
                         activebackground="#2c4378", activeforeground=FG, relief="flat",
                         padx=18, pady=6, command=lambda: self.end_break())
        skip.pack(pady=(0, 28))
        win.bind("<Escape>", lambda e: self.end_break())
        win.update_idletasks()
        w, h = win.winfo_reqwidth(), win.winfo_reqheight()
        win.geometry(f"{w}x{h}+{(sw - w) // 2}+{(sh - h) // 2}")
        win.deiconify()
        win.lift()
        win.focus_force()
        self.update_break(time.monotonic())

    def update_break(self, now: float) -> None:
        remaining = self.break_end - now
        if remaining <= 0:
            self.end_break()
            return
        if self.break_win is not None:
            self.count_label.config(text=str(int(remaining + 0.999)))
            self.progress["value"] = self.break_secs - remaining
            # Pulzáló keret és szám: a figyelemfelkeltéshez (hang nélkül).
            k = (math.sin(now * 5) + 1) / 2
            color = blend("#14213d", "#ffd166", k)
            self.break_win.config(bg=color)
            self.count_label.config(fg=blend(ACCENT, "#ffffff", k))

    def end_break(self) -> None:
        if self.overlay_win is not None:
            self.overlay_win.destroy()
            self.overlay_win = None
        if self.break_win is not None:
            self.break_win.destroy()
            self.break_win = None
        self.state = "working"
        self.next_break = time.monotonic() + self.work_secs

    # ---- beállítások ablak
    def open_settings(self) -> None:
        if self.settings_win is not None:
            self.settings_win.lift()
            self.settings_win.focus_force()
            return

        win = tk.Toplevel(self.root)
        self.settings_win = win
        win.title(f"{APP_NAME} – Beállítások")
        win.resizable(False, False)
        win.attributes("-topmost", True)
        pad = {"padx": 14, "pady": 6}

        frm = ttk.Frame(win, padding=10)
        frm.pack()

        work_var = tk.IntVar(value=self.cfg["work_minutes"])
        break_var = tk.IntVar(value=self.cfg["break_seconds"])
        full_var = tk.BooleanVar(value=self.cfg["fullscreen"])
        auto_var = tk.BooleanVar(value=self.cfg["autostart"])

        ttk.Label(frm, text="Munka időtartam (perc):").grid(row=0, column=0, sticky="w", **pad)
        ttk.Spinbox(frm, from_=1, to=180, width=6, textvariable=work_var).grid(row=0, column=1, **pad)
        ttk.Label(frm, text="Szünet hossza (másodperc):").grid(row=1, column=0, sticky="w", **pad)
        ttk.Spinbox(frm, from_=5, to=300, width=6, textvariable=break_var).grid(row=1, column=1, **pad)
        ttk.Checkbutton(frm, text="Teljes képernyős figyelmeztetés", variable=full_var).grid(
            row=2, column=0, columnspan=2, sticky="w", **pad)
        ttk.Checkbutton(frm, text="Indítás a Windows-szal", variable=auto_var).grid(
            row=3, column=0, columnspan=2, sticky="w", **pad)

        def close() -> None:
            self.settings_win = None
            win.destroy()

        def save() -> None:
            try:
                work = max(1, min(180, int(work_var.get())))
                brk = max(5, min(300, int(break_var.get())))
            except (tk.TclError, ValueError):
                return
            changed = work != self.cfg["work_minutes"]
            self.cfg.update(work_minutes=work, break_seconds=brk,
                            fullscreen=bool(full_var.get()), autostart=bool(auto_var.get()))
            save_config(self.cfg)
            try:
                set_autostart(self.cfg["autostart"])
            except OSError:
                pass
            if changed and self.state == "working":
                self.next_break = time.monotonic() + self.work_secs
            close()

        btns = ttk.Frame(frm)
        btns.grid(row=4, column=0, columnspan=2, pady=(10, 0))
        ttk.Button(btns, text="Mentés", command=save).pack(side="left", padx=4)
        ttk.Button(btns, text="Mégse", command=close).pack(side="left", padx=4)
        win.protocol("WM_DELETE_WINDOW", close)

    # ---- kilépés
    def quit(self) -> None:
        self.icon.stop()
        self.root.quit()

    def run(self) -> None:
        self.root.mainloop()


def main() -> None:
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):
        pass
    if not single_instance():
        return
    App(background="--background" in sys.argv).run()


if __name__ == "__main__":
    main()
