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
wstępne, np. `1.4-beta`, `1.4-beta.2` oraz `1.4-rc.1`. Dzięki temu wydanie beta
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
zablokuje downgrade. Aby wycofać przykładową wersję `1.4-beta` z
`versionCode = 14`:

1. przywróć kod ostatniej bezpiecznej wersji;
2. ustaw nowy `versionName`, np. `1.3-security-rollback.1`;
3. ustaw `versionCode` większy od 14, np. `15`;
4. podpisz APK tym samym kluczem;
5. opublikuj go jako osobne wydanie z identycznym tagiem
   `v1.3-security-rollback.1`;
6. dopiero po sprawdzeniu awaryjnego APK usuń wydanie `v1.4-beta`.

Usunięcie wydania jest zdalnym sygnałem bezpieczeństwa, dlatego nie należy robić
tego tylko w celu porządkowania listy Releases.

## Pierwsze wysłanie kodu

Katalog nie zawiera jeszcze prawidłowo zainicjalizowanego repozytorium Git.
Przed pierwszym wysłaniem wykonaj w głównym folderze projektu:

```powershell
git init
git add .
git status
git commit -m "Wydanie 1.4-beta"
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
versionCode = 14
versionName = "1.4-beta"
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
- `versionName` równe `1.4-beta`;
- `versionCode` równe `14`;
- brak klucza YouTube API, haseł i pliku klucza podpisu.

Jeżeli przekazujesz APK do MobSF lub innego skanera, wybierz podpisany wariant
`release`. Raport, którego pierwsza strona podaje `app-debug.apk`, certyfikat
`Android Debug` albo `android:debuggable=true`, nie opisuje zabezpieczeń
publicznego wydania i będzie poprawnie zgłaszał narzędzia debugowania jako
problemy wysokiego poziomu.

Podpis można zweryfikować narzędziem z Android SDK:

```powershell
apksigner verify --verbose --print-certs .\lewicowYT-1.4-beta.apk
```

Zapisz również sumę kontrolną:

```powershell
Get-FileHash -Algorithm SHA256 .\lewicowYT-1.4-beta.apk
```

## Publikowanie wersji 1.4-beta

1. Poczekaj, aż kontrole GitHub Actions zakończą się powodzeniem.
2. W repozytorium otwórz `Releases → Draft a new release`.
3. Utwórz tag:

```text
v1.4-beta
```

4. Zaznacz wydanie jako **pre-release**.
5. Dołącz dokładnie jeden podpisany APK, np.:

```text
lewicowYT-1.4-beta.apk
```

6. W opisie podaj SHA-256 APK oraz najważniejsze znane ograniczenia.
7. Opublikuj wydanie i sprawdź w aplikacji przycisk `Sprawdź aktualizacje`.

## Kolejne wydania

Przykładowa kolejność:

```text
1.4-beta
1.4-beta.2
1.4-rc.1
1.4
```

Dla każdego wydania zwiększ `versionCode`, podpisz APK tym samym kluczem i użyj
tagu odpowiadającego `versionName`.
