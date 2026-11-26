# Szemkímélő – Android

A Windowsos Szemkímélő Android változata (natív Kotlin + Jetpack Compose).

## Funkciók

- **20-20-20 szünet:** 20 percenként (állítható) teljes képernyős visszaszámláló jelenik meg
  más appok felett: nagy számláló, folyamatjelző, pulzáló keret, `Kihagyom` gomb
- **Hangjelzés** (a telefon értesítési hangja) és opcionális rezgés a szünet elején és az emlékeztetőknél;
  mindkettő külön kapcsolható az appban
- **Zárolt képernyőn is megjelenik:** a telefon felébred, és a visszaszámláló a lezárási képernyő felett látszik
- A `+` / `−` gombok nyomva tartva folyamatosan (egyre gyorsabban) változtatják az értéket
- **Ivás emlékeztető** óránként (az időköz 15–240 perc között állítható), felugró értesítés
- **Ebéd emlékeztető** beállítható időponttal, napi egyszer; elhalasztható 10 percre
- Újraindítás után az emlékeztetők maguktól folytatódnak

## Engedélyek

| Engedély | Mire kell |
|---|---|
| Értesítések | ivás és ebéd emlékeztető, futás jelzése |
| Megjelenítés más appok felett | teljes képernyős visszaszámláló használat közben (nélküle csak értesítést kapsz) |
| Teljes képernyős értesítések | a visszaszámláló zárolt képernyő felett (Android 14+: kézzel kell engedélyezni, az app jelzi) |
| Pontos ébresztők | az emlékeztetők pontos időzítése (automatikusan megkapja) |

## Telepítés (APK)

1. Töltsd le az `.apk` fájlt a [Releases](../../releases) oldalról.
2. Nyisd meg a telefonon, és engedélyezd az ismeretlen forrásból való telepítést.
3. Indítsd el az appot, nyomd meg az `Indítás` gombot, és add meg a kért engedélyeket.

A Play Protect figyelmeztethet, mert az APK nincs a Play Áruházban.

## Fejlesztés

Követelmény: JDK 17, Android SDK (platform 35).

```powershell
cd android
.\gradlew.bat assembleDebug      # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease    # app\build\outputs\apk\release\app-release.apk
```

A `local.properties` fájlban kell megadni az SDK helyét (`sdk.dir=...`); ez nincs a repóban.

A release APK jelenleg a debug kulccsal van aláírva, ami oldaltöltéshez (sideload) elég. Play Áruházhoz
külön feltöltési kulcs kellene.
