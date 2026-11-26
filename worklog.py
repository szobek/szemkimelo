"""Munkaidő-stopper adatkezelése (felület nélkül, így tesztelhető).

Egy "szakasz" a jelenleg mért időtartam. Elindítható, szüneteltethető, folytatható. A szakasz
lezárásakor (részidő vagy leállítás) bejegyzés keletkezik megjegyzéssel. A napi összesítő a
bejegyzések kezdési napja szerint csoportosít.
"""
from __future__ import annotations

import csv
import io
import json
import os
import tempfile
import time
from datetime import date, datetime

HEARTBEAT_SECS = 30  # ennyi időnként mentjük a futó mérés "életjelét" (összeomlás/áramszünet ellen)


def fmt_hms(seconds: float) -> str:
    s = max(0, int(seconds))
    return f"{s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d}"


def fmt_dur(seconds: float) -> str:
    """Rövid, olvasható időtartam: '3 ó 12 p', '45 p', '30 mp'."""
    s = max(0, int(round(seconds)))
    h, m = s // 3600, s % 3600 // 60
    if h:
        return f"{h} ó {m:02d} p"
    if m:
        return f"{m} p"
    return f"{s} mp"


def csv_hours(seconds: int) -> str:
    """Tizedes óra magyar tizedesvesszővel: 5598 mp -> '1,55'."""
    return f"{seconds / 3600:.2f}".replace(".", ",")


def csv_safe(text: str) -> str:
    """Az Excel ne értelmezze képletként a '=', '+', '-', '@' jellel kezdődő megjegyzést."""
    return "'" + text if text[:1] in ("=", "+", "-", "@") else text


class WorkLog:
    def __init__(self, path: str) -> None:
        self.path = path
        self.entries: list[dict] = []  # {"id", "start" (epoch), "seconds", "note"}
        self.next_id = 1
        self.active = False            # van-e megkezdett szakasz
        self.begin_at = 0.0            # a szakasz kezdete (epoch)
        self.accum = 0.0               # a szakasz eddig összegyűlt ideje (mp), a futó rész nélkül
        self.run_since: float | None = None  # ha fut: mióta (epoch); szüneteltetve None
        self.version = 0               # minden változásnál nő (a felület ebből tudja, mikor kell frissíteni)
        self._last_beat = 0.0
        self.load()

    # ---------------------------------------------------------------- tárolás
    def load(self) -> None:
        try:
            with open(self.path, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            return
        self.entries = [e for e in data.get("entries", []) if isinstance(e, dict)]
        self.next_id = max([e.get("id", 0) for e in self.entries] + [0]) + 1
        cur = data.get("current") or {}
        if cur.get("active"):
            self.active = True
            self.begin_at = float(cur.get("begin_at", time.time()))
            self.accum = float(cur.get("accum", 0.0))
            run_since = cur.get("run_since")
            if run_since is not None:
                # Az app nem záródott le rendesen: az utolsó életjelig számítjuk az időt, a mérés szünetel.
                beat = float(cur.get("heartbeat", run_since))
                self.accum += max(0.0, beat - float(run_since))
            self.run_since = None
            self.save()

    def save(self, now: float | None = None) -> None:
        data = {
            "entries": self.entries,
            "current": {
                "active": self.active,
                "begin_at": self.begin_at,
                "accum": self.accum,
                "run_since": self.run_since,
                "heartbeat": now if now is not None else time.time(),
            },
        }
        try:
            os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=os.path.dirname(self.path) or ".", suffix=".tmp")
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=1)
            os.replace(tmp, self.path)
        except OSError:
            pass
        self._last_beat = now if now is not None else time.time()

    # ---------------------------------------------------------------- állapot
    @property
    def state(self) -> str:
        if not self.active:
            return "idle"
        return "running" if self.run_since is not None else "paused"

    def elapsed(self, now: float | None = None) -> float:
        now = time.time() if now is None else now
        if not self.active:
            return 0.0
        return self.accum + (max(0.0, now - self.run_since) if self.run_since is not None else 0.0)

    # ---------------------------------------------------------------- műveletek
    def start(self, now: float | None = None) -> None:
        """Új szakaszt indít, vagy folytatja a szüneteltetettet."""
        now = time.time() if now is None else now
        if self.state == "running":
            return
        if not self.active:
            self.active, self.begin_at, self.accum = True, now, 0.0
        self.run_since = now
        self._changed(now)

    def pause(self, now: float | None = None) -> None:
        now = time.time() if now is None else now
        if self.state != "running":
            return
        self.accum += max(0.0, now - self.run_since)
        self.run_since = None
        self._changed(now)

    def _add_entry(self, seconds: int, note: str) -> dict:
        entry = {"id": self.next_id, "start": self.begin_at, "seconds": seconds, "note": note.strip()}
        self.next_id += 1
        self.entries.append(entry)
        return entry

    def split(self, note: str = "", now: float | None = None) -> dict | None:
        """Részidő: a szakasz eddigi ideje bejegyzés lesz, a mérés új szakasszal megy tovább."""
        now = time.time() if now is None else now
        if not self.active:
            return None
        seconds = int(round(self.elapsed(now)))
        if seconds < 1:
            return None
        entry = self._add_entry(seconds, note)
        was_running = self.state == "running"
        self.begin_at, self.accum = now, 0.0
        self.run_since = now if was_running else None
        self._changed(now)
        return entry

    def stop(self, note: str = "", now: float | None = None) -> dict | None:
        """Leállítás: a maradék idő bejegyzés lesz, és nincs futó szakasz."""
        now = time.time() if now is None else now
        if not self.active:
            return None
        seconds = int(round(self.elapsed(now)))
        entry = self._add_entry(seconds, note) if seconds >= 1 else None
        self.active, self.accum, self.run_since = False, 0.0, None
        self._changed(now)
        return entry

    def edit_note(self, entry_id: int, note: str) -> None:
        for e in self.entries:
            if e["id"] == entry_id:
                e["note"] = note.strip()
                self._changed()
                return

    def delete(self, entry_id: int) -> None:
        self.entries = [e for e in self.entries if e["id"] != entry_id]
        self._changed()

    def beat(self, now: float | None = None) -> None:
        """Futó mérésnél időnként elmenti az életjelet (az app tick-je hívja)."""
        now = time.time() if now is None else now
        if self.state == "running" and now - self._last_beat >= HEARTBEAT_SECS:
            self.save(now)

    def _changed(self, now: float | None = None) -> None:
        self.version += 1
        self.save(now)

    # ---------------------------------------------------------------- összesítő
    @staticmethod
    def _day_of(entry: dict) -> date:
        return datetime.fromtimestamp(entry["start"]).date()

    def day_entries(self, day: date) -> list[dict]:
        return sorted((e for e in self.entries if self._day_of(e) == day), key=lambda e: e["start"])

    def day_total(self, day: date) -> int:
        return sum(int(e["seconds"]) for e in self.entries if self._day_of(e) == day)

    # ---------------------------------------------------------------- export
    def month_entries(self, year: int, month: int) -> list[dict]:
        return sorted((e for e in self.entries
                       if (d := self._day_of(e)).year == year and d.month == month),
                      key=lambda e: e["start"])

    def month_csv(self, year: int, month: int) -> str:
        """A hónap bejegyzései és napi összesítői CSV-ben (pontosvesszős, magyar Excelhez)."""
        out = io.StringIO()
        w = csv.writer(out, delimiter=";", lineterminator="\r\n")
        entries = self.month_entries(year, month)
        w.writerow(["Dátum", "Kezdés", "Időtartam", "Óra", "Megjegyzés"])
        days: dict[date, int] = {}
        for e in entries:
            dt = datetime.fromtimestamp(e["start"])
            secs = int(e["seconds"])
            days[dt.date()] = days.get(dt.date(), 0) + secs
            w.writerow([dt.strftime("%Y.%m.%d."), dt.strftime("%H:%M"), fmt_hms(secs), csv_hours(secs),
                        csv_safe(e.get("note", ""))])
        w.writerow([])
        w.writerow(["Napi összesítő"])
        w.writerow(["Dátum", "", "Összesen", "Óra"])
        for d in sorted(days):
            w.writerow([d.strftime("%Y.%m.%d."), "", fmt_hms(days[d]), csv_hours(days[d])])
        total = sum(days.values())
        w.writerow([f"Összesen ({year}.{month:02d}.)", "", fmt_hms(total), csv_hours(total)])
        return out.getvalue()

    def import_csv_text(self, text: str) -> tuple[int, int, int]:
        """Exportált CSV visszaolvasása. Összefésül: ami már megvan, azt kihagyja.

        Visszatér: (új, már megvolt, hibás sor). Ha a szöveg nem munkaidő-export, ValueError.
        A bejegyzés azonosítása: kezdés (percre), időtartam, megjegyzés.
        """
        rows = list(csv.reader(io.StringIO(text.lstrip("\ufeff")), delimiter=";"))
        if not rows or [c.strip() for c in rows[0][:3]] != ["Dátum", "Kezdés", "Időtartam"]:
            raise ValueError("Ez nem munkaidő-export fájl.")

        def key(start: float, seconds: int, note: str) -> tuple[int, int, str]:
            return int(start // 60), seconds, note

        known = {key(e["start"], int(e["seconds"]), e.get("note", "")) for e in self.entries}
        added = skipped = bad = 0
        for row in rows[1:]:
            if not any(c.strip() for c in row):
                break  # a bejegyzések táblázata itt véget ér (utána a napi összesítő jön)
            try:
                day = datetime.strptime(row[0].strip(), "%Y.%m.%d.")
                hh, mm = row[1].strip().split(":")
                start = day.replace(hour=int(hh), minute=int(mm)).timestamp()
                h, m, s = (int(x) for x in row[2].strip().split(":"))
                seconds = h * 3600 + m * 60 + s
                note = row[4].strip() if len(row) > 4 else ""
            except (ValueError, IndexError):
                bad += 1
                continue
            if note[:1] == "'" and note[1:2] in ("=", "+", "-", "@"):
                note = note[1:]  # az export védő aposztrófját visszavesszük
            if seconds < 1:
                bad += 1
                continue
            k = key(start, seconds, note)
            if k in known:
                skipped += 1
                continue
            known.add(k)
            self.entries.append({"id": self.next_id, "start": start, "seconds": seconds, "note": note})
            self.next_id += 1
            added += 1
        if added:
            self._changed()
        return added, skipped, bad

    def import_csv(self, path: str) -> tuple[int, int, int]:
        if os.path.getsize(path) > 5_000_000:
            raise ValueError("A fájl túl nagy ahhoz, hogy munkaidő-export legyen.")
        with open(path, encoding="utf-8-sig", newline="") as f:
            return self.import_csv_text(f.read())

    def export_month(self, year: int, month: int, path: str) -> None:
        """Fájlba írja a hónapot UTF-8 BOM-mal, hogy az Excel helyesen mutassa az ékezeteket."""
        with open(path, "w", encoding="utf-8-sig", newline="") as f:
            f.write(self.month_csv(year, month))
