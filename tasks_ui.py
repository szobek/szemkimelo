"""A napi teendők ablaka (tkinter)."""
from __future__ import annotations

import tkinter as tk
from datetime import date
from tkinter import messagebox, ttk

from tasks import TaskList
from worklog_ui import BTN, BTN_ACTIVE, CARD, DAYS, MONTHS, ask_note

CHECK, UNCHECK = "☑", "☐"
DONE_FG = "#7d8bab"


class TasksWindow:
    def __init__(self, root: tk.Tk, tasks: TaskList, colors: dict, on_close) -> None:
        self.tasks = tasks
        self.colors = colors
        self.on_close = on_close
        self._seen = None
        self._cache: dict = {}

        bg, fg, accent, muted = colors["bg"], colors["fg"], colors["accent"], colors["muted"]
        win = tk.Toplevel(root)
        self.win = win
        win.title("Szemkímélő – Teendők")
        win.configure(bg=bg)
        win.minsize(460, 460)
        sw, sh = win.winfo_screenwidth(), win.winfo_screenheight()
        w, h = 500, 600
        win.geometry(f"{w}x{h}+{(sw - w) // 2 + 30}+{(sh - h) // 2 + 30}")
        win.protocol("WM_DELETE_WINDOW", self.close)

        tk.Label(win, text="✅  Teendők", font=("Segoe UI", 18, "bold"), bg=bg, fg=fg).pack(pady=(16, 0))
        self.day_label = tk.Label(win, text="", font=("Segoe UI", 10), bg=bg, fg=muted)
        self.day_label.pack()

        def btn(parent, text, cmd, side="left"):
            b = tk.Button(parent, text=text, command=cmd, font=("Segoe UI", 10), bg=BTN, fg=fg,
                          activebackground=BTN_ACTIVE, activeforeground=fg, relief="flat",
                          disabledforeground="#6b7a99", padx=10, pady=4)
            b.pack(side=side, padx=3)
            return b

        # Alsó gombsorok előbb, hogy a lista ne tudja kiszorítani őket.
        bottom = tk.Frame(win, bg=bg)
        bottom.pack(side="bottom", pady=(4, 14))
        btn(bottom, "Szerkesztés", self.edit)
        btn(bottom, "Törlés", self.delete)
        btn(bottom, "Bezárás", self.close)
        mid = tk.Frame(win, bg=bg)
        mid.pack(side="bottom", pady=(4, 0))
        btn(mid, "Kész / visszanyitás", self.toggle)
        btn(mid, "▲ Feljebb", lambda: self.move(-1))
        btn(mid, "▼ Lejjebb", lambda: self.move(1))

        # Új teendő felvétele
        add = tk.Frame(win, bg=bg)
        add.pack(fill="x", padx=20, pady=(12, 8))
        self.var = tk.StringVar()
        self.entry = tk.Entry(add, textvariable=self.var, font=("Segoe UI", 11), bg=CARD, fg=fg,
                              insertbackground=fg, relief="flat")
        self.entry.pack(side="left", fill="x", expand=True, ipady=5)
        self.entry.bind("<Return>", lambda e: self.add())
        btn(add, "Hozzáadás", self.add, side="right")

        self.summary = tk.Label(win, text="", font=("Segoe UI", 10), bg=bg, fg=accent)
        self.summary.pack(anchor="w", padx=22)

        style = ttk.Style(win)
        style.theme_use("clam")
        style.configure("Task.Treeview", background=CARD, fieldbackground=CARD, foreground=fg,
                        rowheight=30, borderwidth=0, bordercolor=CARD, lightcolor=CARD, darkcolor=CARD,
                        font=("Segoe UI", 11))
        style.map("Task.Treeview", background=[("selected", "#2c4378")])

        frame = tk.Frame(win, bg=bg)
        frame.pack(fill="both", expand=True, padx=20, pady=(4, 6))
        self.tree = ttk.Treeview(frame, style="Task.Treeview", columns=("check", "text"),
                                 show="", selectmode="browse", height=6)
        self.tree.column("check", width=40, anchor="center", stretch=False)
        self.tree.column("text", width=380)
        self.tree.tag_configure("done", foreground=DONE_FG)
        scroll = ttk.Scrollbar(frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        self.tree.bind("<Button-1>", self._click)
        self.tree.bind("<Double-1>", lambda e: self.edit())
        self.tree.bind("<Delete>", lambda e: self.delete())

        self.update()
        self.entry.focus_set()

    # ---------------------------------------------------------------- műveletek
    def show(self) -> None:
        self.win.deiconify()
        self.win.lift()
        self.win.focus_force()
        self.entry.focus_set()

    def close(self) -> None:
        self.win.destroy()
        self.on_close()

    def selected_id(self) -> int | None:
        sel = self.tree.selection()
        return int(sel[0]) if sel else None

    def _click(self, event) -> None:
        """A pipa oszlopra kattintva kipipálja / visszanyitja a teendőt."""
        row = self.tree.identify_row(event.y)
        if row and self.tree.identify_column(event.x) == "#1":
            self.tree.selection_set(row)
            self.tasks.toggle(int(row))
            self.update()

    def add(self) -> None:
        if self.tasks.add(self.var.get()):
            self.var.set("")
            self.update()
            # az új (utolsó nyitott) teendő látszódjon
            open_ids = [t["id"] for t in self.tasks.open_tasks()]
            if open_ids:
                self.tree.see(str(open_ids[-1]))

    def toggle(self) -> None:
        tid = self.selected_id()
        if tid is not None:
            self.tasks.toggle(tid)
            self.update()

    def move(self, delta: int) -> None:
        tid = self.selected_id()
        if tid is not None:
            self.tasks.move(tid, delta)
            self.update()

    def edit(self) -> None:
        tid = self.selected_id()
        task = next((t for t in self.tasks.tasks if t["id"] == tid), None) if tid is not None else None
        if task is None:
            return
        text = ask_note(self.win, self.colors, "Teendő szerkesztése", "Teendő:", task["text"])
        if text is not None and text.strip():
            self.tasks.edit(tid, text)
            self.update()

    def delete(self) -> None:
        tid = self.selected_id()
        if tid is None:
            return
        if messagebox.askyesno("Törlés", "Biztosan törlöd a kijelölt teendőt?", parent=self.win):
            self.tasks.delete(tid)
            self.update()

    # ---------------------------------------------------------------- frissítés
    def update(self) -> None:
        today = date.today()
        key = (self.tasks.version, today)
        if key == self._seen:
            return
        self._seen = key
        wd = DAYS[today.weekday()]
        self.day_label.config(text=f"{today.year}. {MONTHS[today.month - 1]} {today.day}., {wd}")

        selected = self.selected_id()
        self.tree.delete(*self.tree.get_children())
        open_tasks = self.tasks.open_tasks()
        done_today = self.tasks.done_on(today)
        for t in open_tasks:
            self.tree.insert("", "end", iid=str(t["id"]), values=(UNCHECK, t["text"]))
        for t in done_today:
            self.tree.insert("", "end", iid=str(t["id"]), values=(CHECK, t["text"]), tags=("done",))
        if selected is not None and self.tree.exists(str(selected)):
            self.tree.selection_set(str(selected))
        self.summary.config(text=f"Nyitott: {len(open_tasks)}   ·   Ma kész: {len(done_today)}")
