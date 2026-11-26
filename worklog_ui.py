"""A munkaidő-stopper ablaka (tkinter)."""
from __future__ import annotations

import time
import tkinter as tk
from datetime import date, datetime, timedelta
from tkinter import filedialog, messagebox, ttk

from worklog import WorkLog, fmt_dur, fmt_hms

BTN = "#22345c"
BTN_ACTIVE = "#2c4378"
CARD = "#1c2d52"
MONTHS = ["január", "február", "március", "április", "május", "június", "július", "augusztus",
          "szeptember", "október", "november", "december"]
DAYS = ["hétfő", "kedd", "szerda", "csütörtök", "péntek", "szombat", "vasárnap"]


def ask_note(parent: tk.Misc, colors: dict, title: str, prompt: str, initial: str = "") -> str | None:
    """Kis párbeszédablak megjegyzés bekéréséhez. Mégse / Esc esetén None."""
    result: list[str | None] = [None]
    win = tk.Toplevel(parent)
    win.title(title)
    win.configure(bg=colors["bg"])
    win.resizable(False, False)
    win.transient(parent)
    tk.Label(win, text=prompt, bg=colors["bg"], fg=colors["fg"], font=("Segoe UI", 11),
             justify="left").pack(padx=20, pady=(18, 6), anchor="w")
    var = tk.StringVar(value=initial)
    entry = tk.Entry(win, textvariable=var, width=46, font=("Segoe UI", 11), bg=CARD, fg=colors["fg"],
                     insertbackground=colors["fg"], relief="flat")
    entry.pack(padx=20, ipady=5)

    def ok(_event=None) -> None:
        result[0] = var.get()
        win.destroy()

    def cancel(_event=None) -> None:
        win.destroy()

    row = tk.Frame(win, bg=colors["bg"])
    row.pack(pady=16)
    for text, cmd in (("Mentés", ok), ("Mégse", cancel)):
        tk.Button(row, text=text, command=cmd, font=("Segoe UI", 10), bg=BTN, fg=colors["fg"],
                  activebackground=BTN_ACTIVE, activeforeground=colors["fg"], relief="flat",
                  padx=16, pady=5).pack(side="left", padx=5)
    win.bind("<Return>", ok)
    win.bind("<Escape>", cancel)
    win.update_idletasks()
    px, py = parent.winfo_rootx(), parent.winfo_rooty()
    win.geometry(f"+{px + 40}+{py + 80}")
    entry.focus_force()
    win.grab_set()
    parent.wait_window(win)
    return result[0]


class WorkLogWindow:
    def __init__(self, root: tk.Tk, log: WorkLog, colors: dict, on_close) -> None:
        self.log = log
        self.colors = colors
        self.on_close = on_close
        self.day = date.today()
        self._cache: dict = {}
        self._seen_version = -1

        bg, fg, accent, muted = colors["bg"], colors["fg"], colors["accent"], colors["muted"]
        win = tk.Toplevel(root)
        self.win = win
        win.title("Szemkímélő – Munkaidő")
        win.configure(bg=bg)
        win.minsize(540, 560)
        sw, sh = win.winfo_screenwidth(), win.winfo_screenheight()
        w, h = 560, 640
        win.geometry(f"{w}x{h}+{(sw - w) // 2}+{(sh - h) // 2}")
        win.protocol("WM_DELETE_WINDOW", self.close)

        tk.Label(win, text="⏱  Munkaidő", font=("Segoe UI", 18, "bold"), bg=bg, fg=fg).pack(pady=(16, 0))
        self.status = tk.Label(win, text="", font=("Segoe UI", 10), bg=bg, fg=muted)
        self.status.pack()
        self.time_label = tk.Label(win, text="0:00:00", font=("Segoe UI", 40, "bold"), bg=bg, fg=accent)
        self.time_label.pack(pady=(2, 6))

        def btn(parent, text, cmd):
            b = tk.Button(parent, text=text, command=cmd, font=("Segoe UI", 10), bg=BTN, fg=fg,
                          activebackground=BTN_ACTIVE, activeforeground=fg, relief="flat",
                          disabledforeground="#6b7a99", padx=12, pady=5)
            b.pack(side="left", padx=4)
            return b

        row = tk.Frame(win, bg=bg)
        row.pack(pady=4)
        self.main_btn = btn(row, "Indítás", self.toggle)
        self.split_btn = btn(row, "Részidő mentése…", self.split)
        self.stop_btn = btn(row, "Leállítás…", self.stop)

        tk.Frame(win, bg=CARD, height=2).pack(fill="x", padx=20, pady=(14, 8))

        nav = tk.Frame(win, bg=bg)
        nav.pack()
        btn(nav, "‹", lambda: self.shift_day(-1))
        self.day_label = tk.Label(nav, text="", font=("Segoe UI", 12, "bold"), bg=bg, fg=fg, width=24)
        self.day_label.pack(side="left")
        btn(nav, "›", lambda: self.shift_day(1))
        btn(nav, "Ma", self.go_today)

        self.total_label = tk.Label(win, text="", font=("Segoe UI", 12), bg=bg, fg=accent)
        self.total_label.pack(pady=(8, 6))

        style = ttk.Style(win)
        style.theme_use("clam")
        style.configure("Work.Treeview", background=CARD, fieldbackground=CARD, foreground=fg,
                        rowheight=28, borderwidth=0, bordercolor=CARD, lightcolor=CARD,
                        darkcolor=CARD, font=("Segoe UI", 10))
        style.configure("Work.Treeview.Heading", background=BTN, foreground=fg, relief="flat",
                        font=("Segoe UI", 10, "bold"))
        style.map("Work.Treeview", background=[("selected", "#2c4378")])
        style.map("Work.Treeview.Heading", background=[("active", BTN_ACTIVE)])

        # Az alsó gombsort előbb csomagoljuk, hogy a lista ne tudja kiszorítani.
        bottom2 = tk.Frame(win, bg=bg)
        bottom2.pack(side="bottom", pady=(0, 14))
        btn(bottom2, "Hónap exportálása…", self.export_month)
        btn(bottom2, "CSV importálása…", self.import_csv)
        btn(bottom2, "Bezárás", self.close)
        bottom = tk.Frame(win, bg=bg)
        bottom.pack(side="bottom", pady=(4, 6))
        btn(bottom, "Megjegyzés szerkesztése", self.edit_note)
        btn(bottom, "Törlés", self.delete)

        frame = tk.Frame(win, bg=bg)
        frame.pack(fill="both", expand=True, padx=20, pady=(0, 6))
        self.tree = ttk.Treeview(frame, style="Work.Treeview", columns=("start", "dur", "note"),
                                 show="headings", selectmode="browse", height=6)
        self.tree.heading("start", text="Kezdés")
        self.tree.heading("dur", text="Időtartam")
        self.tree.heading("note", text="Megjegyzés")
        self.tree.column("start", width=70, anchor="center", stretch=False)
        self.tree.column("dur", width=90, anchor="center", stretch=False)
        self.tree.column("note", width=300)
        scroll = ttk.Scrollbar(frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        self.tree.bind("<Double-1>", lambda e: self.edit_note())

        self.update()

    # ---------------------------------------------------------------- műveletek
    def show(self) -> None:
        self.win.deiconify()
        self.win.lift()
        self.win.focus_force()

    def close(self) -> None:
        self.win.destroy()
        self.on_close()

    def toggle(self) -> None:
        if self.log.state == "running":
            self.log.pause()
        else:
            self.log.start()
        self.update()

    def split(self) -> None:
        # A mért időt a gombnyomás pillanatában rögzítjük: a megjegyzés írása nem számít bele.
        at = time.time()
        if self.log.state == "idle" or self.log.elapsed(at) < 1:
            return
        note = ask_note(self.win, self.colors, "Részidő mentése",
                        f"Megjegyzés a részidőhöz ({fmt_dur(self.log.elapsed(at))}):")
        if note is not None:  # a következő szakasz is a gombnyomástól számít
            self.log.split(note, at)
            self.go_today()

    def stop(self) -> None:
        at = time.time()
        if self.log.state == "idle":
            return
        if self.log.elapsed(at) < 1:
            self.log.stop("", at)
            self.update()
            return
        note = ask_note(self.win, self.colors, "Leállítás",
                        f"Megjegyzés a bejegyzéshez ({fmt_dur(self.log.elapsed(at))}):")
        if note is not None:  # Mégse: a mérés változatlanul megy tovább
            self.log.stop(note, at)
            self.go_today()

    def selected_id(self) -> int | None:
        sel = self.tree.selection()
        return int(sel[0]) if sel else None

    def edit_note(self) -> None:
        entry_id = self.selected_id()
        if entry_id is None:
            return
        entry = next((e for e in self.log.entries if e["id"] == entry_id), None)
        if entry is None:
            return
        note = ask_note(self.win, self.colors, "Megjegyzés szerkesztése", "Megjegyzés:", entry["note"])
        if note is not None:
            self.log.edit_note(entry_id, note)
            self.update()

    def delete(self) -> None:
        entry_id = self.selected_id()
        if entry_id is None:
            return
        if messagebox.askyesno("Törlés", "Biztosan törlöd a kijelölt bejegyzést?", parent=self.win):
            self.log.delete(entry_id)
            self.update()

    def export_month(self) -> None:
        """A kijelzett nap hónapját menti CSV-be (Excelben megnyitható)."""
        y, m = self.day.year, self.day.month
        label = f"{y}. {MONTHS[m - 1]}"
        if not self.log.month_entries(y, m):
            messagebox.showinfo("Exportálás", f"{label} hónapban nincs mentett bejegyzés.", parent=self.win)
            return
        path = filedialog.asksaveasfilename(
            parent=self.win, title=f"{label} exportálása", defaultextension=".csv",
            initialfile=f"munkaido-{y}-{m:02d}.csv", filetypes=[("CSV (Excel)", "*.csv")])
        if not path:
            return
        try:
            self.log.export_month(y, m, path)
        except OSError as exc:
            messagebox.showerror("Exportálás", f"Nem sikerült menteni:\n{exc}", parent=self.win)
            return
        messagebox.showinfo("Exportálás", f"{label} exportálva:\n{path}", parent=self.win)

    def import_csv(self) -> None:
        """Korábban exportált CSV visszatöltése (összefésül, a meglévőt nem duplázza)."""
        path = filedialog.askopenfilename(parent=self.win, title="Exportált munkaidő-CSV importálása",
                                          filetypes=[("CSV", "*.csv"), ("Minden fájl", "*.*")])
        if not path:
            return
        try:
            added, skipped, bad = self.log.import_csv(path)
        except (OSError, ValueError, UnicodeError) as exc:
            messagebox.showerror("Importálás", f"Nem sikerült az importálás:\n{exc}", parent=self.win)
            return
        msg = f"Új bejegyzés: {added}\nMár megvolt (kihagyva): {skipped}"
        if bad:
            msg += f"\nHibás sor (kihagyva): {bad}"
        messagebox.showinfo("Importálás", msg, parent=self.win)
        self.update()

    def shift_day(self, delta: int) -> None:
        self.day += timedelta(days=delta)
        self.update()

    def go_today(self) -> None:
        self.day = date.today()
        self.update()

    # ---------------------------------------------------------------- frissítés
    def _set(self, key: str, widget, **kwargs) -> None:
        """Csak akkor ír a widgetbe, ha az érték tényleg változott."""
        if self._cache.get(key) != kwargs:
            self._cache[key] = kwargs
            widget.config(**kwargs)

    def update(self) -> None:
        log = self.log
        state = log.state
        elapsed = log.elapsed()
        self._set("time", self.time_label, text=fmt_hms(elapsed))
        self._set("status", self.status, text={"idle": "Nincs futó mérés", "running": "Fut",
                                               "paused": "Szüneteltetve"}[state])
        self._set("main", self.main_btn, text={"idle": "Indítás", "running": "Szüneteltetés",
                                               "paused": "Folytatás"}[state])
        usable = "normal" if state != "idle" else "disabled"
        self._set("split", self.split_btn, state=usable)
        self._set("stop", self.stop_btn, state=usable)

        today = date.today()
        wd = DAYS[self.day.weekday()]
        suffix = " (ma)" if self.day == today else ""
        self._set("day", self.day_label, text=f"{self.day.strftime('%Y.%m.%d.')} {wd}{suffix}")

        total = log.day_total(self.day)
        running_part = int(elapsed) if (self.day == today and state != "idle") else 0
        text = f"Összesen: {fmt_dur(total + running_part)}"
        if running_part:
            text += f"  (futó: {fmt_dur(running_part)})"
        self._set("total", self.total_label, text=text)

        key = (log.version, self.day)
        if key != self._seen_version:
            self._seen_version = key
            self.tree.delete(*self.tree.get_children())
            for e in log.day_entries(self.day):
                self.tree.insert("", "end", iid=str(e["id"]), values=(
                    datetime.fromtimestamp(e["start"]).strftime("%H:%M"),
                    fmt_dur(e["seconds"]), e["note"]))
