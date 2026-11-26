# Szemkímélő

Windows tálcaalkalmazás a **20-20-20 szabályhoz**: 20 percenként nézz 20 másodpercig
egy kb. 6 méterre (20 láb) lévő dologra, hogy pihenjen a szemed.

## Funkciók

- Tálcaikon a hátralévő idővel, főablak visszaszámlálással
- Hang nélküli, feltűnő figyelmeztetés a 20 perc leteltekor: sötétített képernyő
  (minden monitoron), nagy visszaszámlálás, pulzáló keret
- Szünet kihagyható (`Esc` vagy gomb)
- **Ebéd emlékeztető** beállítható időponttal (naponta egyszer, a megadott időponttól
  30 percen belül; elhalasztható 10 percre)
- **Ivás emlékeztető** óránként (az időköz 15–240 perc között állítható), kis
  felugró ablakban a jobb alsó sarokban; magától eltűnik
- Szüneteltetés, újraindítás, azonnali szünet a tálcamenüből
- Ha a szünet hosszánál tovább nem használod a gépet, az időzítő magától újraindul
- Beállítható munkaidő, szünethossz, teljes képernyős mód és indítás a Windows-szal
- Egyszerre egy példány fut; az exe újraindítása előhozza a futó ablakot

A beállítások itt vannak: `%APPDATA%\Szemkimelo\config.json`

## Futtatás forrásból

Követelmény: Python 3.10+ (Windows).

```powershell
pip install -r requirements.txt
python szemkimelo.py
```

## Exe készítése

```powershell
python -m PyInstaller --noconfirm --onefile --windowed --name Szemkimelo szemkimelo.py
```

Az eredmény: `dist\Szemkimelo.exe` (önállóan futtatható, Python nem kell hozzá).
