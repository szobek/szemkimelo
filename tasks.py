"""Napi teendők (task lista) adatkezelése, felület nélkül, így tesztelhető.

Egyetlen lista van. A nem kész teendők minden nap megmaradnak (másnapra "átmennek"), a kész teendő
pedig azon a napon számít elvégzettnek, amikor kipipálták.
"""
from __future__ import annotations

import json
import os
import tempfile
import time
from datetime import date, datetime


class TaskList:
    def __init__(self, path: str) -> None:
        self.path = path
        # Sorrendben: {"id", "text", "created" (epoch), "done_at" (epoch vagy None)}
        self.tasks: list[dict] = []
        self.next_id = 1
        self.version = 0  # minden változásnál nő (a felület ebből tudja, mikor kell frissíteni)
        self.load()

    # ---------------------------------------------------------------- tárolás
    def load(self) -> None:
        try:
            with open(self.path, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            return
        self.tasks = [t for t in data.get("tasks", [])
                      if isinstance(t, dict) and isinstance(t.get("text"), str) and "id" in t]
        self.next_id = max([t["id"] for t in self.tasks] + [0]) + 1

    def save(self) -> None:
        try:
            os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=os.path.dirname(self.path) or ".", suffix=".tmp")
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump({"tasks": self.tasks}, f, ensure_ascii=False, indent=1)
            os.replace(tmp, self.path)
        except OSError:
            pass

    def _changed(self) -> None:
        self.version += 1
        self.save()

    # ---------------------------------------------------------------- műveletek
    def _find(self, task_id: int) -> dict | None:
        return next((t for t in self.tasks if t["id"] == task_id), None)

    def add(self, text: str, now: float | None = None) -> dict | None:
        text = text.strip()
        if not text:
            return None
        task = {"id": self.next_id, "text": text,
                "created": time.time() if now is None else now, "done_at": None}
        self.next_id += 1
        self.tasks.append(task)
        self._changed()
        return task

    def toggle(self, task_id: int, now: float | None = None) -> None:
        """Kipipálja a teendőt, vagy (ha kész volt) újra megnyitja."""
        t = self._find(task_id)
        if t is None:
            return
        t["done_at"] = None if t.get("done_at") else (time.time() if now is None else now)
        self._changed()

    def edit(self, task_id: int, text: str) -> None:
        t = self._find(task_id)
        if t is not None and text.strip():
            t["text"] = text.strip()
            self._changed()

    def delete(self, task_id: int) -> None:
        self.tasks = [t for t in self.tasks if t["id"] != task_id]
        self._changed()

    def move(self, task_id: int, delta: int) -> None:
        """Egy nyitott teendőt a többi nyitott teendőhöz képest feljebb (-1) vagy lejjebb (+1) tesz."""
        t = self._find(task_id)
        if t is None or t.get("done_at"):
            return
        open_idx = [i for i, x in enumerate(self.tasks) if not x.get("done_at")]
        pos = open_idx.index(self.tasks.index(t))
        new = pos + (-1 if delta < 0 else 1)
        if not 0 <= new < len(open_idx):
            return
        a, b = open_idx[pos], open_idx[new]
        self.tasks[a], self.tasks[b] = self.tasks[b], self.tasks[a]
        self._changed()

    # ---------------------------------------------------------------- lekérdezések
    @staticmethod
    def _day_of(epoch: float) -> date:
        return datetime.fromtimestamp(epoch).date()

    def open_tasks(self) -> list[dict]:
        return [t for t in self.tasks if not t.get("done_at")]

    def done_on(self, day: date) -> list[dict]:
        return sorted((t for t in self.tasks if t.get("done_at") and self._day_of(t["done_at"]) == day),
                      key=lambda t: t["done_at"])

    def done_in_month(self, year: int, month: int) -> list[dict]:
        return sorted((t for t in self.tasks if t.get("done_at")
                       and (d := self._day_of(t["done_at"])).year == year and d.month == month),
                      key=lambda t: t["done_at"])

    def today_view(self, today: date | None = None) -> list[dict]:
        """A mai lista: az összes nyitott teendő (sorrendben), utána a ma elvégzettek."""
        return self.open_tasks() + self.done_on(today or date.today())

    # ---------------------------------------------------------------- importáláshoz
    def add_imported(self, text: str, created: float, done_at: float | None) -> bool:
        """Importált teendő felvétele. False, ha már megvan (nyitottnál szöveg, késznél kész-perc + szöveg)."""
        for t in self.tasks:
            if t["text"] != text:
                continue
            if done_at is None and not t.get("done_at"):
                return False
            if done_at is not None and t.get("done_at") and int(t["done_at"] // 60) == int(done_at // 60):
                return False
        self.tasks.append({"id": self.next_id, "text": text, "created": created, "done_at": done_at})
        self.next_id += 1
        self._changed()
        return True
