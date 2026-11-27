import os
import sys
import tempfile
import unittest
from datetime import datetime

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from worklog import WorkLog, fmt_dur, fmt_hms  # noqa: E402

T0 = datetime(2026, 11, 26, 9, 0, 0).timestamp()


class WorkLogTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.dir.name, "worklog.json")
        self.log = WorkLog(self.path)

    def tearDown(self):
        self.dir.cleanup()

    def test_start_pause_resume_elapsed(self):
        self.log.start(T0)
        self.assertEqual(self.log.state, "running")
        self.assertEqual(self.log.elapsed(T0 + 600), 600)
        self.log.pause(T0 + 600)
        self.assertEqual(self.log.state, "paused")
        self.assertEqual(self.log.elapsed(T0 + 5000), 600)  # szünetelés közben nem telik
        self.log.start(T0 + 5000)
        self.assertEqual(self.log.elapsed(T0 + 5300), 900)

    def test_split_keeps_running_and_saves_entry(self):
        self.log.start(T0)
        e = self.log.split("reggeli munka", T0 + 1800)
        self.assertEqual(e["seconds"], 1800)
        self.assertEqual(e["note"], "reggeli munka")
        self.assertEqual(self.log.state, "running")
        self.assertEqual(self.log.elapsed(T0 + 1860), 60)  # új szakasz
        self.assertEqual(len(self.log.entries), 1)

    def test_split_while_paused_stays_paused(self):
        self.log.start(T0)
        self.log.pause(T0 + 100)
        self.log.split("x", T0 + 200)
        self.assertEqual(self.log.state, "paused")
        self.assertEqual(self.log.elapsed(T0 + 999), 0)

    def test_stop_saves_rest_and_goes_idle(self):
        self.log.start(T0)
        self.log.split("a", T0 + 1000)
        e = self.log.stop("b", T0 + 1500)
        self.assertEqual(e["seconds"], 500)
        self.assertEqual(self.log.state, "idle")
        self.assertEqual(self.log.day_total(datetime(2026, 11, 26).date()), 1500)

    def test_stop_with_nothing_measured_saves_nothing(self):
        self.log.start(T0)
        self.assertIsNone(self.log.stop("", T0))
        self.assertEqual(self.log.entries, [])
        self.assertEqual(self.log.state, "idle")

    def test_day_grouping_and_order(self):
        d1 = datetime(2026, 11, 26, 9, 0).timestamp()
        d2 = datetime(2026, 11, 27, 8, 0).timestamp()
        self.log.start(d2)
        self.log.stop("második nap", d2 + 60)
        self.log.start(d1)
        self.log.stop("első nap", d1 + 120)
        day1 = self.log.day_entries(datetime(2026, 11, 26).date())
        self.assertEqual([e["note"] for e in day1], ["első nap"])
        self.assertEqual(self.log.day_total(datetime(2026, 11, 27).date()), 60)

    def test_month_csv(self):
        def add(dt, secs, note):
            ts = dt.timestamp()
            self.log.start(ts)
            self.log.stop(note, ts + secs)

        add(datetime(2026, 11, 26, 9, 0), 5598, "Tervezés; \"idézet\"")   # 1:33:18
        add(datetime(2026, 11, 26, 13, 0), 1800, "=1+1")                  # képlet-védelem
        add(datetime(2026, 11, 3, 8, 30), 3600, "")
        add(datetime(2026, 12, 1, 8, 0), 999, "másik hónap")
        self.assertEqual(len(self.log.month_entries(2026, 11)), 3)

        lines = self.log.month_csv(2026, 11).split("\r\n")
        self.assertEqual(lines[0], "Dátum;Kezdés;Időtartam;Óra;Megjegyzés")
        self.assertEqual(lines[1], "2026.11.03.;08:30;1:00:00;1,00;")         # időrendben
        self.assertEqual(lines[2], '2026.11.26.;09:00;1:33:18;1,55;"Tervezés; ""idézet"""')
        self.assertEqual(lines[3], "2026.11.26.;13:00;0:30:00;0,50;'=1+1")
        self.assertNotIn("másik hónap", "\n".join(lines))
        self.assertIn("2026.11.26.;;2:03:18;2,06", lines)                     # napi összeg
        self.assertIn("Összesen (2026.11.);;3:03:18;3,06", lines)             # havi összeg

    def test_import_roundtrip_merges_without_duplicates(self):
        def add(dt, secs, note):
            ts = dt.timestamp()
            self.log.start(ts)
            self.log.stop(note, ts + secs)

        add(datetime(2026, 11, 26, 9, 0, 41), 5598, "Tervezés; \"idézet\"")
        add(datetime(2026, 11, 26, 13, 0), 1800, "=1+1")
        add(datetime(2026, 11, 3, 8, 30), 3600, "")
        text = self.log.month_csv(2026, 11)

        # 1) ugyanabba a naplóba visszaolvasva semmi sem duplázódik
        self.assertEqual(tuple(self.log.import_csv_text(text)), (0, 3, 0, 0))
        self.assertEqual(len(self.log.entries), 3)

        # 2) üres naplóba (új telefon) minden visszajön, a megjegyzések is épek
        other = WorkLog(os.path.join(self.dir.name, "other.json"))
        self.assertEqual(tuple(other.import_csv_text(text)), (3, 0, 0, 0))
        notes = sorted(e["note"] for e in other.entries)
        self.assertEqual(notes, sorted(["", "=1+1", "Tervezés; \"idézet\""]))
        self.assertEqual(other.day_total(datetime(2026, 11, 26).date()), 5598 + 1800)
        # a megmaradt azonosítók egyediek, és új bejegyzés is felvehető
        self.assertEqual(len({e["id"] for e in other.entries}), 3)
        # fájlba mentve is megmarad
        self.assertEqual(len(WorkLog(other.path).entries), 3)

    def test_import_rejects_foreign_file_and_counts_bad_rows(self):
        with self.assertRaises(ValueError):
            self.log.import_csv_text("a;b;c\r\n1;2;3\r\n")
        with self.assertRaises(ValueError):
            self.log.import_csv_text("")
        text = ("Dátum;Kezdés;Időtartam;Óra;Megjegyzés\r\n"
                "2026.11.26.;09:00;1:00:00;1,00;jó\r\n"
                "hibás;09:00;1:00:00;1,00;rossz dátum\r\n"
                "2026.11.26.;10:00;0:00:00;0,00;nulla\r\n"
                "\r\nNapi összesítő\r\n")
        self.assertEqual(tuple(self.log.import_csv_text(text)), (1, 0, 2, 0))

    def test_export_month_file_has_bom(self):
        self.log.start(T0)
        self.log.stop("x", T0 + 60)
        out = os.path.join(self.dir.name, "e.csv")
        self.log.export_month(2026, 11, out)
        with open(out, "rb") as f:
            self.assertTrue(f.read().startswith(b"\xef\xbb\xbf"))

    def test_edit_note_and_delete(self):
        self.log.start(T0)
        e = self.log.stop("régi", T0 + 60)
        self.log.edit_note(e["id"], "  új  ")
        self.assertEqual(self.log.entries[0]["note"], "új")
        self.log.delete(e["id"])
        self.assertEqual(self.log.entries, [])

    def test_persistence_and_clean_restart(self):
        self.log.start(T0)
        self.log.split("mentett", T0 + 600)
        reloaded = WorkLog(self.path)
        self.assertEqual(len(reloaded.entries), 1)
        self.assertEqual(reloaded.next_id, 2)

    def test_crash_recovery_counts_until_last_heartbeat(self):
        self.log.start(T0)
        self.log.save(T0 + 120)  # utolsó életjel 2 perc után, aztán "összeomlás"
        reloaded = WorkLog(self.path)
        self.assertEqual(reloaded.state, "paused")
        self.assertEqual(reloaded.elapsed(T0 + 99999), 120)

    def test_version_changes_on_edits(self):
        v = self.log.version
        self.log.start(T0)
        self.assertGreater(self.log.version, v)

    def test_corrupt_file_is_ignored(self):
        with open(self.path, "w", encoding="utf-8") as f:
            f.write("{nem json")
        self.assertEqual(WorkLog(self.path).entries, [])

    def test_formatting(self):
        self.assertEqual(fmt_hms(3725), "1:02:05")
        self.assertEqual(fmt_dur(3725), "1 ó 02 p")
        self.assertEqual(fmt_dur(300), "5 p")
        self.assertEqual(fmt_dur(30), "30 mp")


if __name__ == "__main__":
    unittest.main()
