import os
import sys
import tempfile
import unittest
from datetime import date, datetime

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from tasks import TaskList  # noqa: E402
from worklog import WorkLog  # noqa: E402


def at(day: int, h: int, m: int, month: int = 11) -> float:
    return datetime(2026, month, day, h, m).timestamp()


class TaskListTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.dir.name, "tasks.json")
        self.tl = TaskList(self.path)

    def tearDown(self):
        self.dir.cleanup()

    def test_add_toggle_edit_delete(self):
        a = self.tl.add("  levelek  ", at(26, 8, 0))
        self.assertEqual(a["text"], "levelek")
        self.assertIsNone(self.tl.add("   "))  # üres szöveg nem teendő
        self.tl.edit(a["id"], "levelek megválaszolása")
        self.assertEqual(self.tl.tasks[0]["text"], "levelek megválaszolása")
        self.tl.edit(a["id"], "  ")  # üresre nem írható át
        self.assertEqual(self.tl.tasks[0]["text"], "levelek megválaszolása")
        self.tl.toggle(a["id"], at(26, 9, 30))
        self.assertEqual(self.tl.open_tasks(), [])
        self.tl.toggle(a["id"])  # visszanyitás
        self.assertEqual(len(self.tl.open_tasks()), 1)
        self.tl.delete(a["id"])
        self.assertEqual(self.tl.tasks, [])

    def test_open_tasks_carry_over_and_done_stays_on_its_day(self):
        a = self.tl.add("régi, nem kész", at(25, 8, 0))
        b = self.tl.add("tegnap kész", at(25, 8, 5))
        self.tl.toggle(b["id"], at(25, 17, 0))
        c = self.tl.add("ma kész", at(26, 8, 10))
        self.tl.toggle(c["id"], at(26, 11, 0))
        today = date(2026, 11, 26)
        # ma: a nyitott (tegnapról átjött) + a ma kész; a tegnapi kész nem
        self.assertEqual([t["text"] for t in self.tl.today_view(today)], ["régi, nem kész", "ma kész"])
        self.assertEqual([t["text"] for t in self.tl.done_on(date(2026, 11, 25))], ["tegnap kész"])
        self.assertEqual([t["id"] for t in self.tl.open_tasks()], [a["id"]])

    def test_move_only_among_open_tasks(self):
        a = self.tl.add("A")
        d = self.tl.add("D (kész)")
        self.tl.toggle(d["id"])
        b = self.tl.add("B")
        c = self.tl.add("C")
        self.tl.move(c["id"], -1)
        self.assertEqual([t["text"] for t in self.tl.open_tasks()], ["A", "C", "B"])
        self.tl.move(c["id"], -1)
        self.assertEqual([t["text"] for t in self.tl.open_tasks()], ["C", "A", "B"])
        self.tl.move(c["id"], -1)  # már legfelül van: nem változik
        self.assertEqual([t["text"] for t in self.tl.open_tasks()], ["C", "A", "B"])
        self.tl.move(b["id"], 1)   # már legalul van: nem változik
        self.assertEqual([t["text"] for t in self.tl.open_tasks()], ["C", "A", "B"])
        self.tl.move(d["id"], -1)  # kész teendő nem mozgatható
        self.assertEqual(len(self.tl.tasks), 4)
        self.assertEqual(a["text"], "A")

    def test_persistence_and_corrupt_file(self):
        t = self.tl.add("megmarad", at(26, 8, 0))
        self.tl.toggle(t["id"], at(26, 9, 0))
        again = TaskList(self.path)
        self.assertEqual(again.tasks, self.tl.tasks)
        self.assertEqual(again.add("új")["id"], t["id"] + 1)
        with open(self.path, "w", encoding="utf-8") as f:
            f.write("{nem json")
        self.assertEqual(TaskList(self.path).tasks, [])

    def test_version_changes(self):
        v = self.tl.version
        t = self.tl.add("x")
        self.assertGreater(self.tl.version, v)
        v = self.tl.version
        self.tl.toggle(t["id"])
        self.assertGreater(self.tl.version, v)


class TaskCsvTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.log = WorkLog(os.path.join(self.dir.name, "worklog.json"))
        self.tl = TaskList(os.path.join(self.dir.name, "tasks.json"))
        self.log.start(at(26, 9, 0))
        self.log.stop("munka", at(26, 10, 0))
        a = self.tl.add("számla; kiküldése", at(20, 8, 0))
        self.tl.toggle(a["id"], at(26, 11, 30))
        b = self.tl.add("=SUM(A1)", at(21, 8, 0))
        self.tl.toggle(b["id"], at(27, 8, 0))       # másik nap, ugyanabban a hónapban
        c = self.tl.add("december", at(2, 8, 0, 12))
        self.tl.toggle(c["id"], at(2, 9, 0, 12))    # másik hónap
        self.tl.add("nyitott teendő", at(25, 8, 0))

    def tearDown(self):
        self.dir.cleanup()

    def test_export_contains_done_in_month_and_all_open(self):
        text = self.log.month_csv(2026, 11, self.tl)
        self.assertIn("Elvégzett teendők", text)
        self.assertIn('2026.11.26.;11:30;"számla; kiküldése"', text)
        self.assertIn("2026.11.27.;08:00;'=SUM(A1)", text)
        self.assertNotIn("december", text)
        self.assertIn("Nyitott teendők\r\nDátum;Idő;Teendő\r\n2026.11.25.;08:00;nyitott teendő", text)
        # teendők nélküli export a régi formátum marad
        self.assertNotIn("teendők", self.log.month_csv(2026, 11))

    def test_roundtrip_restores_tasks_without_duplicates(self):
        text = self.log.month_csv(2026, 11, self.tl)
        other_log = WorkLog(os.path.join(self.dir.name, "o_worklog.json"))
        other = TaskList(os.path.join(self.dir.name, "o_tasks.json"))
        r = other_log.import_csv_text(text, other)
        self.assertEqual(tuple(r), (1, 0, 0, 3))
        self.assertEqual([t["text"] for t in other.open_tasks()], ["nyitott teendő"])
        self.assertEqual([t["text"] for t in other.done_on(date(2026, 11, 26))], ["számla; kiküldése"])
        self.assertEqual([t["text"] for t in other.done_on(date(2026, 11, 27))], ["=SUM(A1)"])
        # másodszor semmi sem duplázódik
        r = other_log.import_csv_text(text, other)
        self.assertEqual(tuple(r), (0, 1, 0, 0))
        self.assertEqual(len(other.tasks), 3)

    def test_import_without_tasklist_ignores_task_sections(self):
        text = self.log.month_csv(2026, 11, self.tl)
        other_log = WorkLog(os.path.join(self.dir.name, "o_worklog.json"))
        r = other_log.import_csv_text(text)  # nincs TaskList: csak a munkaidő jön vissza
        self.assertEqual(tuple(r), (1, 0, 0, 0))

    def test_old_export_without_tasks_still_imports(self):
        old = self.log.month_csv(2026, 11)
        other_log = WorkLog(os.path.join(self.dir.name, "o_worklog.json"))
        other = TaskList(os.path.join(self.dir.name, "o_tasks.json"))
        self.assertEqual(tuple(other_log.import_csv_text(old, other)), (1, 0, 0, 0))
        self.assertEqual(other.tasks, [])


if __name__ == "__main__":
    unittest.main()
