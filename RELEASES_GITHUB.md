# Publikowanie aktualizacji przez GitHub Releases

Aplikacja sprawdza publiczne wydania repozytorium:

```text
https://github.com/emmunioo/lewicowyt
```

## Jak działa sprawdzanie aktualizacji

Aplikacja odpytuje listę maksymalnie 100 publicznych wydań:

```text
https://api.github.com/repos/emmunioo/lewicowyt/releases?per_page=100
```

Pomija szkice, używa porównania wersji zbliżonego do SemVer i obsługuje wersje
wstępne, np. `1.5-beta`, `1.6-beta` oraz `1.6-rc.1`. Dzięki temu wydanie beta
może wykryć kolejną betę, a później wydanie stabilne.

Automatyczna kontrola odbywa się razem ze sprawdzaniem YouTube, najwyżej raz na
2 godziny. Ręczny przycisk wykonuje jedno żądanie w ciągu 15 minut. Aplikacja
akceptuje tylko adresy HTTPS należące do skonfigurowanego repozytorium.

APK jest pobierane do prywatnego cache bez otwierania przeglądarki. Przed
uruchomieniem systemowego instalatora sprawdzane są:

- SHA-256, gdy GitHub udostępnia pole `digest`;
- `applicationId`;
- certyfikat podpisujący względem zainstalowanej aplikacji;
- zgodność `versionName` z tagiem;
- wyższy `versionCode`.

Uprawnienie `REQUEST_INSTALL_PACKAGES` pozwala wywołać instalator, ale nie
pozwala samodzielnie zatwierdzić instalacji. Zwykła aplikacja musi otrzymać
potwierdzenie użytkownika w systemowym oknie Androida.

## Awaryjne wycofanie wydania

Jeśli na liście GitHub Releases nie ma wydania z APK odpowiadającego
zainstalowanemu `versionName`, aplikacja uznaje wersję za wycofaną. Wtedy:

1. nowsze dostępne wydanie staje się obowiązkową aktualizacją bezpieczeństwa;
2. gdy nowszego nie ma, wybierane jest najwyższe starsze wydanie;
3. pobieranie odbywa się niezależnie od przełącznika automatycznych aktualizacji;
4. użytkownik otrzymuje trwałe powiadomienie i nadal potwierdza instalację
   w systemie.

Nie wolno po prostu wskazać dawnego APK z niższym `versionCode`, ponieważ Android
zablokuje downgrade. Aby wycofać przykładową wersję `1.5-beta` z
`versionCode = 15`:

1. przywróć kod ostatniej bezpiecznej wersji;
2. ustaw nowy `versionName`, np. `1.4-security-rollback.1`;
3. ustaw `versionCode` większy od 15, np. `16`;
4. podpisz APK tym samym kluczem;
5. opublikuj go jako osobne wydanie z identycznym tagiem
   `v1.4-security-rollback.1`;
6. dopiero po sprawdzeniu awaryjnego APK usuń wydanie `v1.5-beta`.

Usunięcie wydania jest zdalnym sygnałem bezpieczeństwa, dlatego nie należy robić
tego tylko w celu porządkowania listy Releases.

## Pierwsze wysłanie kodu

Katalog nie zawiera jeszcze prawidłowo zainicjalizowanego repozytorium Git.
Przed pierwszym wysłaniem wykonaj w głównym folderze projektu:

```powershell
git init
git add .
git status
git commit -m "Wydanie 1.6-beta"
git branch -M main
git remote add origin https://github.com/emmunioo/lewicowyt.git
git push -u origin main
```

Przed `git commit` koniecznie przejrzyj wynik `git status`. W repozytorium nie
mogą znaleźć się:

- pliki `.jks` lub `.keystore`;
- hasła i pliki konfiguracji podpisu;
- `local.properties`;
- gotowe pliki APK/AAB;
- raporty awarii JVM, zrzuty pamięci i lokalne pliki IDE.

Odpowiednie wzorce są już zapisane w `.gitignore`.

## Konfiguracja repozytorium aktualizacji

W `gradle.properties` ustawiono:

```properties
UPDATE_REPOSITORY=emmunioo/lewicowyt
```

Repozytorium musi być publiczne, aby aplikacja mogła sprawdzać wydania bez
osadzania tokenu GitHub w APK.

## Klucz podpisu — decyzja przed pierwszym publicznym APK

Wszystkie publiczne aktualizacje muszą mieć:

- ten sam `applicationId`: `pl.lewicowyt.notifier`;
- coraz większy `versionCode`;
- ten sam certyfikat podpisujący.

Android odrzuci aktualizację podpisaną innym kluczem. Utrata klucza uniemożliwi
wydanie zwykłej aktualizacji, dlatego przechowuj plik i hasła w co najmniej dwóch
zaszyfrowanych kopiach offline.

Nazwa właściciela, miejscowość i inne pola wpisane podczas tworzenia klucza są
publicznie widoczne w certyfikacie każdego APK. Jeżeli nie chcesz publikować
danych osobowych, przed pierwszym publicznym wydaniem utwórz nowy klucz z
pseudonimem lub neutralnymi danymi projektu. Po publikacji nie zmieniaj już tego
klucza.

Tworzenie klucza:

```text
Build → Generate Signed App Bundle or APK → APK → Create new…
```

Pliku klucza nie przechowuj w katalogu projektu ani w chmurze razem z hasłem.
Przed pierwszym publicznym wydaniem utwórz nowe hasło offline i nie przekazuj
go w komunikatorze ani rozmowie z asystentem. Zachowaj je w menedżerze haseł
oraz w osobnej, zaszyfrowanej kopii awaryjnej.

## Ustawienia bieżącego wydania

Plik `app/build.gradle.kts` powinien zawierać:

```kotlin
versionCode = 16
versionName = "1.6-beta"
```

Każda kolejna publikacja musi zwiększyć `versionCode`, nawet jeśli zmienia się
wyłącznie przyrostek wersji.

## Generowanie podpisanego APK w Android Studio

1. Wybierz `Build → Generate Signed App Bundle or APK`.
2. Zaznacz `APK`.
3. Wskaż właściwy plik klucza, alias i oba hasła.
4. Wybierz wariant `release`.
5. Włącz podpis V2; V1 może pozostać włączony jako dodatkowa zgodność.
6. Zakończ kreator i zachowaj wskazaną ścieżkę do APK.

Standardowe artefakty Gradle trafiają do sąsiedniego katalogu
`../KOMPILACJA/app/outputs/`. Kreator podpisanego APK może zapisać końcowy plik
w dowolnym folderze wskazanym w polu `Destination Folder`.

Projekt celowo nie przechowuje danych podpisu w Gradle ani GitHub Actions.
Wygenerowany przez CI wariant release jest niepodpisanym artefaktem kontrolnym,
nie plikiem przeznaczonym do publikacji.

## Kontrola APK przed publikacją

W Android Studio użyj `Build → Analyze APK` i sprawdź:

- nazwę pakietu `pl.lewicowyt.notifier`;
- `versionName` równe `1.6-beta`;
- `versionCode` równe `16`;
- brak klucza YouTube API, haseł i pliku klucza podpisu.

Jeżeli przekazujesz APK do MobSF lub innego skanera, wybierz podpisany wariant
`release`. Raport, którego pierwsza strona podaje `app-debug.apk`, certyfikat
`Android Debug` albo `android:debuggable=true`, nie opisuje zabezpieczeń
publicznego wydania i będzie poprawnie zgłaszał narzędzia debugowania jako
problemy wysokiego poziomu.

Podpis można zweryfikować narzędziem z Android SDK:

```powershell
apksigner verify --verbose --print-certs .\lewicowYT-1.6-beta.apk
```

Zapisz również sumę kontrolną:

```powershell
Get-FileHash -Algorithm SHA256 .\lewicowYT-1.6-beta.apk
```

## Publikowanie wersji 1.6-beta

1. Poczekaj, aż kontrole GitHub Actions zakończą się powodzeniem.
2. W repozytorium otwórz `Releases → Draft a new release`.
3. Utwórz tag:

```text
v1.6-beta
```

4. Zaznacz wydanie jako **pre-release**.
5. Dołącz dokładnie jeden podpisany APK, np.:

```text
lewicowYT-1.6-beta.apk
```

6. W opisie podaj SHA-256 APK oraz najważniejsze znane ograniczenia.
7. Opublikuj wydanie i sprawdź w aplikacji przycisk `Sprawdź aktualizacje`.

Historyczny zweryfikowany artefakt bazowej wersji 1.5-beta (nie używać jego
SHA-256 dla nowego APK 1.6-beta):

- nazwa: `lewicowYT-1.5-beta.apk`;
- rozmiar: 13 161 454 bajty (12,55 MiB);
- SHA-256:
  `41bff61a26ddebf32f09064b4fa9e17a3b9a407b18c9d29316e98f3443995eac`;
- [VirusTotal](https://www.virustotal.com/gui/file/41bff61a26ddebf32f09064b4fa9e17a3b9a407b18c9d29316e98f3443995eac);
- [MobSF 1.5-beta](https://emmunioo.github.io/lewicowyt/lewicowYT-1.5-beta-MobSF.pdf):
  A, 69/100 (`LOW RISK`), 0 ustaleń wysokiego poziomu.

### Zakres zmian 1.6-beta

W opisie wydania należy uwzględnić:

- bezpieczne śledzenie przekierowań GitHub Release Assets oraz zachowanie
  dotychczasowej weryfikacji pobranego APK;
- czytelniejszy ekran aktualizacji z rozmiarem i opisem zmian oraz awaryjny
  przycisk otwierający stronę właściwego wydania po błędzie pobierania;
- lokalne Ulubione w Historii i Powiadomieniach, chronione przed automatyczną
  retencją razem z używaną miniaturą;
- tryb wysokiego kontrastu z minimalnym kontrastem tekstu 4,5:1 oraz poprawki
  TalkBack/powiększonego tekstu;
- migrację zachowującą dane do schematu 24 i AndroidX SQLite Bundled. Należy
  jasno napisać, że 1.6-beta tylko potwierdza dostępność FTS5, a produkcyjny
  indeks, wyszukiwarka i magazyn opisów Zstd BLOB należą do 1.7-beta;
- automatyczną pierwszą synchronizację, kolejkę przyszłych alarmów,
  WakeLock, watchdog oraz wstrzymanie sieci w trybie Nie przeszkadzać;
- trwałą kopię awaryjną koloru akcentu i poprawione mapowanie kanału oraz
  awatara Myśleć Głębiej;
- jedno ustawienie aplikacji otwierającej wszystkie linki YouTube: system,
  chooser, YouTube, NewPipe albo przeglądarka, z bezpiecznym fallbackiem;
- kopiowanie URL po długim przytrzymaniu materiału w Historii lub
  Powiadomieniach;
- rozszerzoną prywatną diagnostykę synchronizacji z `syncId`, stabilnymi
  `reasonCode`, kontekstem twórcy/źródła, czasami etapów i snapshotami stanu,
  bez sekretów oraz danych prywatnych.

Przed publikacją zastąp informacje historyczne 1.5 wynikami dotyczącymi
dokładnie finalnego, podpisanego APK 1.6-beta. Nie wolno przepisywać starego
SHA-256, VirusTotal ani MobSF do nowego wydania.

### Zakres zmian bazowej wersji 1.5-beta

W informacji o bazowej wersji 1.5-beta uwzględniono:

- globalne i indywidualne ustawienia historii oraz powiadomień osobno dla
  filmów, streamów i Shortów;
- ukrywanie globalnie wyłączonych filtrów i pomijanie wyłączonych kart Web;
- nową obsługę kafelka twórcy: checkbox wyboru, rozwijane ustawienia i długie
  przytrzymanie otwierające kanał;
- RSS-first, pięć równoległych kanałów historii i etapy po 14 dni w kolejności
  Filmy, Shorty, Streamy;
- poprawione daty, kursory, rozpoznawanie kart i klasyfikację zgodną między
  Data API oraz YouTube Web;
- wykrywanie powiadomień bez API przez różnicę stabilnych identyfikatorów RSS;
- sześć równoległych źródeł synchronizacji i lokalną adaptacyjną kolejność;
- tekstowy, ograniczony klient YouTube Web bez pobierania obrazów i zasobów
  strony;
- współdzielenie identycznych miniatur po SHA-256 oraz zachowanie JXL przy
  aktualizacji;
- dołączone awatary 176×176 JXL i cotygodniową kontrolę ich SHA-256;
- rozszerzone zabezpieczenia, migracje bazy i testy regresji;
- pominięte w poprzednim skrócie 1.4-beta poprawki aktualizatora: kontrolę
  wydań co 2 godziny, prywatne pobieranie APK, pełną walidację pliku,
  obowiązkowe wydanie bezpieczeństwa i 15-minutowy cache ręcznego sprawdzenia.

Android nie pozwala zwykłej aplikacji zatwierdzić instalacji bez działania
użytkownika. „Automatyczna aktualizacja” oznacza automatyczne wykrycie,
pobranie i sprawdzenie APK; końcową instalację zatwierdza użytkownik.

## Kolejne wydania

Przykładowa kolejność:

```text
1.5-beta
1.6-beta
1.6-rc.1
1.6
```

Dla każdego wydania zwiększ `versionCode`, podpisz APK tym samym kluczem i użyj
tagu odpowiadającego `versionName`.
