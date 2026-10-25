# Békés megyei foci – Android MVP

## Tartalom

Android Java + Firebase Auth + Cloud Firestore alapú fejlesztői tesztalkalmazás. Megjeleníti a Firestore `matches` mérkőzéseit és eseményeit, `players` játékosait. Google-belépés, admin jogosultság ellenőrzése, meccs/játékos felvitel és gól, öngól, sárga/piros lap rögzítése. A szurkolók bejelentkezés nélkül olvashatják az adatokat.

**Nem kész még:** MLSZ adatimport és heti szinkron, valódi push értesítések (gól/öngól/meccs előtti 30 perc), csapatonkénti értesítési beállítás, többadminos kezelőfelület, élő meccsóra, versenykiírás teljes történeti adatbázisa. Ezeket külön meg kell valósítani. A ZIP nem kész APK, a GitHub fogja fordítani. Fordítás ebben a környezetben nem volt ellenőrizhető.

## Feltöltés GitHubra

A ZIP *tartalmát* töltsd fel a privát `Bekes-megyei-foci` repository gyökerébe, ne a külső mappát. A `.github/workflows/android.yml` rejtett könyvtárat is őrizd meg. A GitHub Actions / Android APK munkafolyamatból tölthető le a debug APK. A debug APK nem Play Áruházas kiadás.

## Firebase-beállítások

1. A Firebase Console > Firestore Database > Rules lapon másold be a `firestore.rules` tartalmát, és Publish. Ez nyilvános olvasást, kizárólag admin írást enged.
2. A Firebase Console > Authentication > Sign-in method alatt a Google legyen Enabled (már megtörtént).
3. A Google-bejelentkezéshez a telepített APK aláírásának SHA-1 (ajánlott SHA-256 is) ujjlenyomatát fel kell venni a Firebase Project Settings > Your apps > Android app résznél. A GitHub Actions debug aláírása futásonként változhat, ezért a stabil működéshez tartós, biztonságosan tárolt saját aláírókulcs és annak SHA-1-e szükséges. Az első debug APK-ban emiatt a Google-belépés nem feltétlenül működik.
4. A Google-belépés után az Authentication > Users listában keresd meg a saját UID-det. Firestore Database > Data > `admins` collection: a dokumentum azonosítója pontosan a UID legyen, benne `enabled` mező boolean `true`. Ezt csak a projekt tulajdonosa állítsa be a Firebase konzolban. Más admin felvételéhez ugyanez szükséges. Ne add meg a konzolhoz való hozzáférést másoknak emiatt.
5. Firestore gyűjtemények: `matches`, `players`, `admins`. Az alkalmazás üres adatbázissal indul. A mérkőzések és játékosok csak jóváhagyott admin által tölthetők fel.

## Biztonság

A `google-services.json` Firebase klienskonfiguráció, nem adminjog és nem titkos szerverkulcs. A repository maradjon privát. A biztonságot a Firestore Rules adja, ne kapcsold be a test mode-ot. Az adatstruktúra és a szabályok MVP szintűek; éles üzem előtt szerveroldali validáció és tesztelés szükséges.

## v3 – Tabella és következő mérkőzések

Új menüpontok: **Tabella** (csoportonként, befejezettként jelölt meccsekből számolva) és **Következő meccsek** (dátum szerinti sorrend). Az admin új mérkőzésnél csoportot és fordulót is megadhat. A tabella nem hivatalos MLSZ-rangsor: pontlevonás, külön versenyszabály és holtverseny esetén eltérhet.

A 2026/27-es teljes valós MLSZ eredménylista és meccsnaptár továbbra sincs importálva. Az alkalmazás Firestore adatokból dolgozik, és üres adatbázis esetén üres táblát mutat. Az értesítési szolgáltatás még nem kész. A projektet nem fordítottuk le helyben.
