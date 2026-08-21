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

## Opcjonalne aktualizacje różnicowe Xdelta3/VCDIFF

Od 1.7-beta wydanie może dodatkowo zawierać małą poprawkę różnicową. Pełny APK
pozostaje zawsze obowiązkowym i nadrzędnym artefaktem wydania. Aplikacja użyje
delty wyłącznie wtedy, gdy manifest, dokładna wersja oraz SHA-256 zainstalowanego
bazowego APK pasują, poprawka oszczędza co najmniej 20%, a wszystkie metadane
zasobów GitHub są spójne. W każdym innym przypadku bez komunikatu dla
użytkownika pobiera pełny APK.

Po rekonstrukcji aplikacja wymaga dokładnej sumy SHA-256 oficjalnego pełnego
APK, a następnie wykonuje istniejącą kontrolę pakietu, `versionName`,
`versionCode` i certyfikatu podpisującego. Manifest wydania nigdy nie zastępuje
tych kontroli. Pliki tymczasowe są przechowywane tylko w prywatnym cache.

Generator `Generate-Xdelta.ps1`/`Generate-Xdelta.bat`:

- przyjmuje wyłącznie dwa wcześniej zbudowane i podpisane APK;
- nie uruchamia Gradle i niczego nie podpisuje;
- pobiera przypięte Xdelta3 3.2.0 strumieniowo z limitem 1 MiB, maksymalnie
  pięcioma przekierowaniami HTTPS:443 i allowlistą `github.com` →
  `release-assets.githubusercontent.com`;
- sprawdza SHA-256 oficjalnego ZIP i rozpakowanego EXE przed uruchomieniem;
- sprawdza oba APK przez `apksigner` i `apkanalyzer`;
- świadomie tworzy prosty VCDIFF bez armor, Adler-32, secondary compression
  i application header; zaufanie opiera na SHA-256 source, patcha i dokładnego
  finalnego targetu, a nie na niekryptograficznym Adler-32;
- wykonuje rekonstrukcję kontrolną bit po bicie i ponowną walidację pakietu,
  `versionName`, `versionCode` oraz aktywnego certyfikatu;
- tworzy patch pod nazwą `.tmp`, a finalną nazwę nadaje dopiero po self-teście;
- zapisuje manifest UTF-8 do `.tmp`, zamyka go, ponownie parsuje i sprawdza
  wszystkie wymagane pola przed atomowym zastąpieniem finalnego JSON;
- po awarii usuwa pliki tymczasowe oraz zachowuje poprzedni poprawny manifest
  i poprzednie finalne delty.

Opcje `-a` i `-n` pozostają świadomym elementem formatu mobilnego. Armor Xdelta
używałby application header i BLAKE3, których mobilny dekoder nie potrzebuje,
ponieważ updater przed dekodowaniem sprawdza SHA-256 source i patcha, a po nim
wymaga dokładnego SHA-256 oficjalnego pełnego APK. `-P 0` dotyczy wyszukiwania
duplikatów podczas kompresji i nie jest przełącznikiem `VCD_TARGET`.

Generator delty 1.7 wymaga tego samego aktywnego certyfikatu OLD i NEW. Różny
signer powoduje bezpieczne przerwanie; legalna rotacja może zostać obsłużona
osobno w przyszłości.

Nie wolno generować publicznej delty z APK debug ani ze wstępnego APK do
analizy. Gdy finalny podpisany wariant 1.7-beta jest gotowy, generator należy
uruchomić względem finalnego, publicznego APK wersji bazowej.

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
git commit -m "Wydanie 1.7-beta"
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
versionCode = 18
versionName = "1.7-beta"
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
- `versionName` równe `1.7-beta`;
- `versionCode` równe `18`;
- brak klucza YouTube API, haseł i pliku klucza podpisu.

Jeżeli przekazujesz APK do MobSF lub innego skanera, wybierz podpisany wariant
`release`. Raport, którego pierwsza strona podaje `app-debug.apk`, certyfikat
`Android Debug` albo `android:debuggable=true`, nie opisuje zabezpieczeń
publicznego wydania i będzie poprawnie zgłaszał narzędzia debugowania jako
problemy wysokiego poziomu.

Podpis można zweryfikować narzędziem z Android SDK:

```powershell
apksigner verify --verbose --print-certs .\lewicowYT-1.7-beta.apk
```

Zapisz również sumę kontrolną:

```powershell
Get-FileHash -Algorithm SHA256 .\lewicowYT-1.7-beta.apk
```

## Publikowanie wersji 1.7-beta

1. Poczekaj, aż kontrole GitHub Actions zakończą się powodzeniem.
2. W repozytorium otwórz `Releases → Draft a new release`.
3. Utwórz tag:

```text
v1.7-beta
```

4. Zaznacz wydanie jako **pre-release**.
5. Zawsze dołącz finalny podpisany APK, np.:

```text
lewicowYT-1.7-beta.apk
```

6. Opcjonalnie wygeneruj i dołącz do tego samego wydania:

```text
lewicowYT-1.6.1-beta-to-1.7-beta.xdelta
lewicowYT-update.json
```

Jeżeli poprawka nie daje co najmniej 20% oszczędności albo generator zgłosi
błąd, opublikuj wyłącznie pełny APK. Nie twórz ręcznie manifestu.

7. W opisie podaj SHA-256 APK oraz najważniejsze znane ograniczenia.
8. Opublikuj wydanie i sprawdź w aplikacji przycisk `Sprawdź aktualizacje`.

Historyczne artefakty 1.5-beta, 1.6-beta i 1.6.1-beta nie mogą być źródłem
SHA-256 ani raportów dla nowego APK 1.7-beta.

### Zakres zmian 1.7-beta

- lokalna wyszukiwarka Historii oparta na FTS5, z filtrami i paginowanym
  limitem wyników;
- pobieranie opisów wyłącznie dla już zapisanych materiałów, jako ostatni
  niekrytyczny etap partii: partiami z `snippet.description` Data API przy
  aktywnym kluczu, z fallbackiem YouTube Web, oraz magazyn Zstd level 5/UTF-8;
- ręczne wyszukiwanie starszych materiałów obserwowanego twórcy z YouTube Web
  i obowiązkowe potwierdzenie kanału przed dodaniem do Ulubionych;
- opcjonalna mniejsza aktualizacja różnicowa Xdelta3/VCDIFF zamiast pełnego
  APK, z bezwarunkowym cichym powrotem do pełnego pliku (patrz sekcja
  „Opcjonalne aktualizacje różnicowe Xdelta3/VCDIFF” wyżej);
- prywatna, zanonimizowana wersja powiadomień na ekranie blokady;
- kontrola poprawnej linii następstwa certyfikatu aktualizacji;
- przypięte SHA-256 narzędzi `cjxl.exe` i `yt-dlp.exe` oraz bezpieczny generator
  archiwum źródłowego bez lokalnych danych środowiska.

Pełny, techniczny opis wszystkich zmian: strona projektu
<https://emmunioo.github.io/lewicowyt> po publikacji wydania.

### Zakres zmian 1.6.1-beta

- obsługa ReVanced i innych klientów YouTube;
- respektowanie domyślnej aplikacji ustawionej w Androidzie;
- możliwość wskazania dowolnej innej aplikacji;
- ulepszony wybór „Pytaj za każdym razem”, w tym dostęp do przeglądarki;
- skumulowany ekran zmian 1.6 i 1.6.1 dla aktualizacji bezpośrednio z 1.5.

Historyczny zweryfikowany artefakt bazowej wersji 1.5-beta:

- nazwa: `lewicowYT-1.5-beta.apk`;
- rozmiar: 13 161 454 bajty (12,55 MiB);
- SHA-256:
  `41bff61a26ddebf32f09064b4fa9e17a3b9a407b18c9d29316e98f3443995eac`;
- [VirusTotal](https://www.virustotal.com/gui/file/41bff61a26ddebf32f09064b4fa9e17a3b9a407b18c9d29316e98f3443995eac);
- [MobSF 1.5-beta](https://emmunioo.github.io/lewicowyt/lewicowYT-1.5-beta-MobSF.pdf):
  A, 69/100 (`LOW RISK`), 0 ustaleń wysokiego poziomu.

### Zakres zmian 1.6-beta (historyczne, już opublikowane)

Skrót: bezpieczne przekierowania GitHub Release Assets, czytelniejszy ekran
aktualizacji, lokalne Ulubione, wysoki kontrast i pełny audyt dostępności,
migracja na AndroidX SQLite Bundled, automatyczna pierwsza synchronizacja z
kolejką alarmów/WakeLock/watchdog i wstrzymaniem sieci w trybie Nie
przeszkadzać, trwała kopia awaryjna koloru akcentu, jedno wspólne ustawienie
otwierania linków YouTube, kopiowanie URL długim przytrzymaniem oraz
rozszerzona prywatna diagnostyka. **1.6-beta tylko potwierdza dostępność FTS5
w silniku bazy — produkcyjna wyszukiwarka, indeks i magazyn opisów Zstd BLOB
zostały dopiero wprowadzone w 1.7-beta.** Pełny opis znajduje się na stronie
projektu <https://emmunioo.github.io/lewicowyt>.

### Zakres zmian bazowej wersji 1.5-beta (historyczne, już opublikowane)

Skrót: osobne ustawienia historii/powiadomień per typ materiału i twórca,
RSS-first z pięcioma równoległymi kanałami i etapami 14-dniowymi, poprawiona
klasyfikacja i daty zgodne między Data API a YouTube Web, powiadomienia bez
API przez różnicę identyfikatorów RSS, współdzielone miniatury po SHA-256,
dołączone awatary JXL oraz rozszerzone zabezpieczenia i migracje bazy. Pełny
opis znajduje się na stronie projektu <https://emmunioo.github.io/lewicowyt>.

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
