# Audyt bezpieczeństwa

Data ostatniej aktualizacji przeglądu kodu: 21 sierpnia 2026 r. (audyt różnicowy
binarny 1.6.1-beta→1.7-beta na finalnych podpisanych APK).

Wersja kodu: `1.7-beta` (`versionCode 18`).

Wersja 1.7-beta tworzy schemat 25 z osobnym indeksem FTS5 i magazynem opisów
Zstd/UTF-8. Wyszukiwanie zwykłej Historii jest lokalne. Sieć jest używana tylko
przy jawnym wyszukiwaniu starszych materiałów oraz niekrytycznym, limitowanym
uzupełnianiu opisów już zapisanych rekordów. Przy aktywnym kluczu opisy pochodzą
z oficjalnego `videos.list?part=snippet`; klucz pozostaje w zaszyfrowanym
magazynie i nie trafia do logów. Błąd lub brak pozycji uruchamia dotychczasowy
fallback publicznego odtwarzacza Web. Starszy materiał przed dodaniem do
Ulubionych musi zostać ponownie powiązany z kanałem wybranego twórcy.

Powiadomienia mają `VISIBILITY_PRIVATE` i zanonimizowaną wersję publiczną.
Walidacja aktualizacji wymaga aktywnego podpisującego albo poprawnej linii
następstwa, zamiast dowolnego przecięcia całych historii certyfikatów. Narzędzia
`cjxl.exe` i `yt-dlp.exe` są uruchamiane tylko przy zgodności przypiętego SHA-256.

Wersja 1.6.1-beta rozszerza wspólny launcher linków o ReVanced, inne klienty
YouTube oraz aplikację wybraną przez użytkownika. Przekazywany jest wyłącznie
standardowy URL; aplikacja nie korzysta z SDK tych klientów i nie zapisuje
listy zainstalowanych aplikacji.

Wersja 1.6-beta naprawia pobieranie APK przez standardowe przekierowanie
GitHub Releases. Przekierowania są obsługiwane ręcznie, mają limit pięciu
kroków i muszą prowadzić przez HTTPS na `github.com`,
`objects.githubusercontent.com` albo host w domenie `githubusercontent.com`.
Nie są przesyłane dane uwierzytelniające, a końcowy APK nadal podlega kontroli
rozmiaru, SHA-256, pakietu, wersji i certyfikatu podpisującego.

Jeden dołączony silnik AndroidX SQLite Bundled otwiera cały plik bazy; nie są
mieszane równoległe połączenia systemowe i bundled. Migracja do schematu 25
zachowuje rekordy i Ulubione, tworzy produkcyjny indeks FTS5 oraz magazyn pełnych
opisów jako BLOB Zstd level 5 albo UTF-8, zależnie od rzeczywistego zysku.
FTS5 otrzymuje osobną oczyszczoną reprezentację tekstową, nigdy skompresowany BLOB.

Kandydat źródłowy 1.7-beta jest przygotowany do dalszej walidacji i podpisania
przez użytkownika w Android Studio, ale nie ma jeszcze finalnego APK ani raportów.
Nie wolno przypisywać mu sum SHA-256, VirusTotal ani MobSF wcześniejszych wydań.

## Zakres

Sprawdzono kod Kotlin, manifest i zasoby Androida, konfigurację Gradle, skrypty
startowe dla Windows i systemów uniksowych, katalog twórców, dokumentację oraz
integracje sieciowe YouTube, DNS i GitHub Releases.

Przeanalizowano również raport statyczny MobSF 4.5.1 (98 stron), pełny `logcat`,
zrzut `dumpsys` oraz zapis ruchu sieciowego z 25 lipca 2026 r. Raport dotyczył
APK zbudowanego przed poprawkami opisanymi niżej, dlatego po wygenerowaniu
nowego podpisanego APK wymaga powtórzenia.

Drugi raport MobSF 4.5.1 (88 stron) wykonany po dodaniu walidacji klucza dotyczy
jednak `app-debug.apk`, a nie publicznego wariantu release. Jego wynik 49/100
(`MEDIUM RISK`) nie jest wynikiem wydania: wszystkie trzy ustalenia wysokiego
poziomu wynikają z celowych właściwości kompilacji debug (`debuggable=true`,
certyfikat Android Debug i narzędziowy kod debug).

## Wynik i interpretacja MobSF

Raport podpisanego wydania 1.5-beta z 1 sierpnia 2026 r. potwierdza pakiet
`pl.lewicowyt.notifier`, `versionCode 15`, `versionName 1.5-beta` i podpis v2.
MobSF przyznał ocenę A, 69/100 (`LOW RISK`): 0 ustaleń wysokiego, 8 średniego
i 1 informacyjne. Raport jest dostępny jako
[PDF](https://emmunioo.github.io/lewicowyt/lewicowYT-1.5-beta-MobSF.pdf), a
odpowiadający mu wynik jest dostępny w
[VirusTotal](https://www.virustotal.com/gui/file/41bff61a26ddebf32f09064b4fa9e17a3b9a407b18c9d29316e98f3443995eac).

Poniższe uwagi obejmują również wcześniejsze raporty użyte podczas utwardzania
aplikacji:

- ocena statyczna badanego APK: `A`, 67/100, `LOW RISK`, bez ustaleń wysokiego
  poziomu w podsumowaniu;
- trzy wartości zgłoszone jako możliwe sekrety nie należą do aplikacji:
  `androidx.work.Data-95ed6082-b8e9-46e8-a73f-ff56f00f5d9d` jest znacznikiem
  wartości `null` WorkManagera, a `08b926448d86528e697981ddd30459f7` oraz
  `149fd8ad55885d3fe3549a37a0163243` są hashami schematu bazy WorkManager/Room;
- czwarty ciąg zgłoszony wyłącznie w skanie debug,
  `258EAFA5-E914-47DA-95CA-C5AB0DC85B11`, jest publiczną stałą protokołu
  WebSocket RFC 6455 pochodzącą z OkHttp, a nie sekretem;
- ostrzeżenie SQL injection jest fałszywym alarmem: zmienne wartości trafiają do
  zapytań przez argumenty `?`, a dynamicznie tworzona jest jedynie liczba
  placeholderów. Polecenia `CREATE`, `ALTER`, indeksy, `VACUUM` i `PRAGMA` są
  stałymi aplikacji;
- aplikacja nie używa `Random`; wskazanie słabego generatora oraz większość
  wskazań logowania i operacji plikowych pochodziły z kodu AndroidX i innych
  zależności;
- użycie SHA-1 w `AndroidApiRequestHeaders` nie służy szyfrowaniu ani
  weryfikacji integralności. Google wymaga dokładnie odcisku SHA-1 certyfikatu
  podpisującego w nagłówku `X-Android-Cert`, gdy klucz API jest ograniczony do
  aplikacji Android. Zastąpienie go SHA-256 zepsułoby to ograniczenie;
- wskazania `SystemJobService`, `DiagnosticsReceiver` oraz stałych WorkManagera
  dotyczą starszego APK objętego raportem. Bieżący kod nie zależy od
  WorkManagera i jego komponenty nie powinny występować w nowym APK.
  `ProfileInstallReceiver` służy narzędziom profilowania i jest chroniony
  `android.permission.DUMP`, którego zwykła aplikacja nie może uzyskać;
- raport powielił analizę bibliotek natywnych dla czterech ABI. Wymagane
  biblioteki JXL mają NX, kod pozycyjnie niezależny, stack canary, pełne RELRO,
  brak RPATH/RUNPATH i usunięte symbole. Nieużywany przez tę jednoprocesową
  aplikację `libdatastore_shared_counter.so`, który nie miał stack canary ani
  usuniętych symboli, został wyłączony z pakowania;
- `logcat` nie zawierał awarii, ANR ani `OutOfMemoryError`. Pokazał natomiast
  błąd HTTP 504 jednego kanału oraz zachowanie usuniętej później integracji
  Piped; ta część raportu nie opisuje bieżącego kodu;
- zrzut systemu potwierdził utworzenie kanału powiadomień i uruchomienie
  poprzedniego harmonogramu. Nie opisuje obecnego mechanizmu AlarmManager i nie
  obejmował wiarygodnej próby z wygaszonym ekranem;
- zapis ruchu zawierał wyłącznie pięć żądań usług
  `com.google.android.gms` do rejestracji urządzenia/GCM. Nie zarejestrował
  żądania procesu `pl.lewicowyt.notifier`, więc jest niewystarczający do oceny
  ruchu aplikacji. Nie wolno publikować tych raportów: zapis zawiera token
  `AidLogin`, a pełny zrzut systemu także stałe innych zainstalowanych aplikacji.

## Zastosowane zabezpieczenia

- aplikacja i klient HTTP odrzucają ruch inny niż HTTPS;
- konfiguracja bezpieczeństwa sieci wyłącza ruch nieszyfrowany i jawnie ufa
  wyłącznie systemowemu magazynowi certyfikatów, nie certyfikatom użytkownika ani
  narzędzi MITM;
- wspólny klient HTTP nie wykonuje automatycznych przekierowań. Jedyny
  obsługiwany ręcznie mechanizm przekierowań dotyczy obrazów i ponownie sprawdza
  dozwoloną domenę na każdym kroku;
- wspólny klient sieciowy używa domyślnie AdGuard DNS-over-HTTPS z dwoma
  przypiętymi adresami startowymi; aktywny Prywatny DNS Androida ma pierwszeństwo;
- błąd `NXDOMAIN` nie uruchamia awaryjnego resolvera systemowego, dzięki czemu
  blokada domeny nie jest obchodzona;
- kopia zapasowa danych aplikacji jest wyłączona, aby baza, ustawienia i podany
  przez użytkownika klucz API nie trafiały do kopii chmurowej;
- identyfikatory filmów są sprawdzane przed zapisaniem lub otwarciem, a odnośniki
  do materiałów są budowane lokalnie jako adresy YouTube;
- komunikaty błędów HTTP nie zawierają parametrów zapytania, więc nie ujawniają
  klucza API ani tokenu kontynuacji;
- obrazy mogą być pobierane wyłącznie przez HTTPS z domen obrazów YouTube/Google,
  także po przekierowaniu;
- pobieranie obrazu ma limit 8 MiB, dane źródłowe limit 4096 px na wymiar i
  16 777 216 pikseli, a właściwy bitmapowy wynik dekodowania jest próbkowany do
  maksymalnie 1280 px i 2 097 152 pikseli. Stary cache sprzed tej ochrony jest
  odrzucany;
- odpowiedzi YouTube Web, RSS i Data API mają limity rozmiaru, a tytuły,
  autorzy, klucze klienta oraz tokeny kontynuacji są ograniczane i walidowane
  przed dalszym użyciem;
- RSS potwierdza feed-level `channelId` albo `playlistId` względem oczekiwanego
  `ResolvedSource`. Brak, zły format, sprzeczność lub obca tożsamość odrzuca całe
  źródło przed zapisem Historii, kursora i powiadomień;
- tekstowy klient odrzuca odpowiedzi binarne dla ścieżek metadanych. Integracja
  YouTube Web nie uruchamia WebView ani nie pobiera CSS, skryptów lub obrazów
  strony;
- historia każdego źródła zapisuje najpierw małą odpowiedź RSS, a dopiero potem
  uruchamia stronicowanie Data API albo YouTube Web. Duplikaty są scalane po
  identyfikatorze filmu;
- parser `ytInitialData` funkcji „Znajdź starszy” sprawdza strukturę przed
  `JSONObject` i przechodzi drzewo iteracyjnym DFS z twardym limitem 64 poziomów,
  50 000 węzłów, 2048 elementów kontenera i 100 kandydatów;
- tryb YouTube Web wysyła od razu żądanie właściwej karty i przed parsowaniem
  sprawdza parametry faktycznie zaznaczonego `tabRenderer`. Jeżeli kanał nie ma
  karty transmisji albo Shortów, odpowiedź zastępcza strony głównej nie jest
  przypisywana do żądanego rodzaju;
- parser historii akceptuje aktualny `lockupViewModel` wyłącznie jako
  `LOCKUP_CONTENT_TYPE_VIDEO`, odczytuje tytuł i datę z właściwej sekcji
  metadanych oraz ignoruje lockupy playlist;
- migracja bazy do wersji 15 ponawia klasyfikację bez zerowania `kind`.
  Nieudana klasyfikacja zachowuje dotychczasowy rodzaj, a potwierdzona karta lub
  odtwarzacz poprawia rekord w miejscu;
- bieżący schemat bazy ma wersję 25 i przechowuje m.in. stan kart kanału,
  model kolejności, metadane awatarów oraz stabilny snapshot RSS dla
  powiadomień. Migracje są wykonywane sekwencyjnie także przy aktualizacji
  bezpośrednio ze starszej instalacji;
- SQLite Bundled zapewnia jednolity zestaw funkcji na wspieranych Androidach.
  W 1.7-beta FTS5 indeksuje tytuł, twórcę i oczyszczony opis. Pełny opis jest
  osobnym BLOB z metadanymi kodeka, a dane użytkownika nie są składane do
  dynamicznych poleceń schematu;
- zapytania zwracające listy historii, skrzynki i statystyk mapują wiersze
  bezpośrednio ze `SQLiteStatement`, bez drugiej pełnej kopii w `MatrixCursor`.
  Zapytania listowe mają limity; wyniki FTS5 są stronicowane po 40 pozycji,
  mają twardy limit 100 i nie ładują całej historii do pamięci;
- automatyczne czyszczenie historii pomija Ulubione, a czyszczenie cache obrazów
  zachowuje zawartość nadal wskazywaną przez ulubiony materiał;
- migracja bazy do wersji 10 usuwa niepotwierdzone rekordy pozostawione przez
  dawną integrację zewnętrzną; zostają ponownie pobrane wyłącznie z YouTube;
- nieprawidłowe i skrajnie przyszłe daty z RSS, Data API lub YouTube Web
  są pomijane zamiast zastępowania ich bieżącym czasem;
- klucz YouTube Data API jest szyfrowany AES-256-GCM, a materiał klucza szyfrującego
  pozostaje w Android Keystore; sekret nie trafia do `rememberSaveable`, Bundle ani
  jawnego DataStore. Starszy jawny wpis jest migrowany i usuwany;
- nowy klucz nie jest zapisywany ani aktywowany przed poprawnym żądaniem
  kontrolnym `channels.list` do YouTube Data API. Odrzucenie klucza, wyłączona
  usługa, wyczerpany limit, awaria sieci i nieprawidłowy dokument odpowiedzi nie
  nadpisują poprzedniego klucza. Żądania Data API przekazują również nagłówki
  pakietu i SHA-1 certyfikatu, dzięki czemu działają ograniczenia klucza dla
  aplikacji Android;
- `PendingIntent` powiadomień są niemutowalne;
- identyfikatory powiadomień są dodatnimi, unikalnymi wartościami przypisanymi
  transakcyjnie do pełnego `videoId`, zamiast wynikać z kolizyjnego `hashCode()`;
- miniatury powiadomień są pobierane z ustalonej domeny YouTube, mają limit 5 MiB
  i 4096 px na wymiar, są skalowane do 512×288 przed przekazaniem do Androida
  oraz nie są zapisywane w cache aplikacji;
- cache Historii i wewnętrznych Powiadomień jest adresowany SHA-256 treści.
  Identyczne obrazy współdzielą jeden plik, a usuwanie uwzględnia wszystkie
  aktualne odwołania;
- startowe awatary 176×176 są dołączone jako JXL z manifestem SHA-256. Kontrola
  sieciowa odbywa się najwyżej raz w tygodniu i zastępuje plik wyłącznie po
  stwierdzeniu zmiany;
- globalne i indywidualne reguły rodzajów są sprawdzane przed zapisem oraz
  ponownie przed dostarczeniem powiadomienia. Wyłączenie historii ma
  pierwszeństwo nad ustawieniem powiadomień i pomija odpowiednią kartę Web;
- aplikacja nie ma WebView, dynamicznego wykonywania kodu, dostępu do plików
  użytkownika, lokalizacji, kontaktów, mikrofonu ani kamery;
- audyt różnicowy binarny 1.6.1-beta→1.7-beta (21 sierpnia 2026) potwierdził,
  że wszystkie natywne biblioteki JXL/Brotli/AndroidX Graphics Path/SQLite
  Bundled mają pełne RELRO (RELRO+BIND_NOW) na czterech ABI. Wyjątkiem są nowe
  biblioteki `libzstd-jni-1.5.7-6.so` (1.7-beta): arm64-v8a nie ma segmentu
  `PT_GNU_RELRO` wcale, pozostałe trzy ABI mają RELRO bez `BIND_NOW`. Pliki są
  bit-identyczne z oficjalnym AAR `com.github.luben:zstd-jni:1.5.7-6`
  przypiętym w `verification-metadata.xml` — to właściwość builda upstream, nie
  modyfikacja projektu. Ryzyko praktyczne niskie: zstd dekompresuje wyłącznie
  BLOB-y opisów zapisane wcześniej lokalnie przez samą aplikację (patrz limit
  1 MB i prealokowany bufor w `DescriptionCodec`), a osłabione RELRO ma
  znaczenie dopiero przy już istniejącym prymitywie zapisu w pamięci;
- Gradle pobiera zależności wyłącznie z Google Maven, Maven Central i
  Gradle Plugin Portal; sumy artefaktów używanych do kompilacji są zapisane w
  `gradle/verification-metadata.xml`, a suma dystrybucji i plik Wrapper są
  weryfikowane SHA-256. Android Studio może pobierać nieużywane w APK archiwa
  `*-sources.jar`, `*-javadoc.jar` i przypięte `gradle-8.13-src.zip`; tylko te
  archiwa źródłowe oraz dokumentacyjne mają ograniczoną regułę zaufania po
  nazwie. Brak narzędzia do weryfikacji kończy skrypt błędem.
- akcje GitHub Actions są przypięte do pełnych identyfikatorów commitów, a CI
  testuje, lintuje i kompiluje zarówno wariant debug, jak i release;
- aktualizator pomija szkice i wydania bez poprawnego APK, ogranicza adresy do
  skonfigurowanego repozytorium GitHub oraz nie proponuje bet użytkownikowi
  przyszłej wersji stabilnej. Przygotowanie APK/Xdelta jest objęte procesowym
  single-flight: automatyczny i ręczny caller współdzielą pracę dla tego samego
  targetu, a różne targety nie zapisują równolegle wspólnych plików `.part`;
- release, testy, lint i kompilacja Kotlin konsumują zamrożony pakiet
  `bundled_avatars` bez sieci i bez modyfikacji `src/main/assets`. Pobranie oraz
  kompresję awatarów wykonuje wyłącznie jawne zadanie
  `:app:refreshBundledAvatars`, niepowiązane z `preReleaseBuild`;
- szczegółowe logi wyjątków sieciowych są kompilowane wyłącznie w wariancie
  debug. Wariant release nie zapisuje do `logcat` nazw wybranych twórców,
  adresów ich kanałów ani stosów wyjątków. Opcjonalny lokalny dziennik
  diagnostyczny jest domyślnie wyłączony, ograniczony rozmiarem, redaguje dane
  i eksportuje standardowy tekst GZIP bez klucza API ani tokenów;
- pojedynczy niedostępny kanał nie ponawia całej synchronizacji dziesiątek
  poprawnie sprawdzonych źródeł. Dokładny alarm ponawia awarię całkowitą albo
  obejmującą co najmniej połowę prób najwyżej dwa razy, co 15 minut.
- adaptacyjny model kolejności jest przechowywany wyłącznie w lokalnej bazie.
  Nie zawiera tytułów, klucza API ani wyborów użytkownika, nie wysyła
  telemetrii i nie wykonuje własnych połączeń. Błędy oraz pierwsza inicjalizacja
  nie są traktowane jak okres bez publikacji. Rutynowy stan bez trafienia jest
  utrwalany nie częściej niż raz na 6 godzin, aby ograniczyć zapisy SQLite;

## Ryzyka pozostające z założenia

- klucz YouTube Data API jest własnością użytkownika. Jest zaszyfrowany w pamięci
  aplikacji, maskowany w interfejsie i wyłączony z kopii zapasowej, ale proces
  działający z uprawnieniami roota może przejąć sekret podczas używania go przez
  aplikację;
- tryb bez klucza zależy od RSS i nieoficjalnego formatu strony YouTube.
  Zmiany po stronie YouTube mogą powodować błędy dostępności;
- AdGuard DNS widzi nazwy rozwiązywanych domen i adres IP urządzenia. Jeśli oba
  adresy startowe DoH są niedostępne, aplikacja przechodzi na pięć minut na resolver
  systemowy, co zachowuje dostępność kosztem chwilowego braku aplikacyjnego DoH;
- aktualizator pobiera APK do prywatnego cache aplikacji i przekazuje je
  systemowemu instalatorowi. Aplikacja weryfikuje SHA-256 udostępniony przez
  GitHub, pakiet, wersję i zgodność certyfikatu, ale ostateczne zatwierdzenie
  instalacji nadal należy do użytkownika i Androida;
- dokładny alarm może obudzić urządzenie w Doze, ale wymaga specjalnego dostępu
  na Androidzie 12+. „Wymuś zatrzymanie”, odebranie dostępu, brak dozwolonej
  sieci albo agresywna polityka OEM wobec usługi pierwszoplanowej mogą nadal
  uniemożliwić synchronizację. Kod nie może zagwarantować wykonania na każdym
  urządzeniu dokładnie co do minuty;
- aplikacja odczytuje rzeczywisty stan przez
  `PowerManager.isIgnoringBatteryOptimizations(packageName)` i dopiero po
  działaniu użytkownika wysyła systemowe żądanie dotyczące własnego pakietu.
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` jest jednak uprawnieniem podlegającym
  restrykcyjnej ocenie Google Play i przed publikacją w tym sklepie wymaga
  ponownej oceny zgodności z jego zasadami;
- lokalny `AppUpdateTracker` zapisuje wyłącznie ostatni potwierdzony
  `versionCode`. Czasy instalacji pochodzą z `PackageManager`; mechanizm nie
  wysyła telemetrii i nie pokazuje okna „Co nowego” przy pierwszej instalacji.

## Wynik automatycznej walidacji

Celowana walidacja kandydata 1.7-beta z 9 sierpnia 2026 r. objęła kompilację
Kotlin aplikacji i androidTest oraz 212/212 testów jednostkowych debug bez
błędów i pominięć. Dwa nowe testy instrumentacyjne kodeka opisów i rozszerzony
test migracji schematu 22/23→25 zostały skompilowane, lecz nie uruchomione bez
podłączonego urządzenia. Nie generowano APK. Pełny lint, test release i test
urządzeniowy pozostają do wykonania przed publikacją.

Historyczna końcowa walidacja źródeł kandydata 1.6-beta z 8 sierpnia 2026 r. objęła
210/210 testów jednostkowych osobno dla wariantu debug i release. Lint obu
wariantów zakończył się bez błędów (44 ostrzeżenia niewstrzymujące), a APK
debug oraz niepodpisany APK release zostały poprawnie złożone poza repozytorium
w katalogu kompilacji. Testów urządzeniowych nie powtórzono w tej sesji,
ponieważ Gradle nie wykrył podłączonego emulatora; ostatni dostępny wynik
urządzeniowy pozostaje pozytywny, ale przed publikacją zalecany jest krótki test
ręczny podpisanego APK na fizycznym urządzeniu.

Pełny przebieg lokalny bazowego kodu `1.5-beta` obejmował przebudowaną historię,
selektywne ustawienia rodzajów, klasyfikację, lokalny model kolejności,
współdzielenie obrazów i wbudowane awatary. Ostatni przebieg wykonano
1 sierpnia 2026 r. przed podpisaniem APK:

- 182 testy jednostkowe wariantu debug i 182 wariantu release: 0 niepowodzeń,
  0 błędów i 0 pominiętych testów;
- zgodność wszystkich 52 kluczy gotowego modelu początkowego z katalogiem
  źródeł;
- kompilacja Kotlin, testy oraz Android Lint debug/release zakończone
  powodzeniem; oba raporty lint mają 0 błędów krytycznych i 0 błędów
  (po 43 ostrzeżenia niewstrzymujące wydania);
- zadanie przygotowania 49 awatarów JXL zakończone powodzeniem, a jego
  konfiguracja została sprawdzona z włączonym Gradle Configuration Cache;
- dla rozpoczętej wersji 1.6-beta uruchomiono 14 testów aktualizatora i wyboru
  wydań: 0 niepowodzeń, 0 błędów i 0 pominiętych testów. Testy obejmują
  akceptację hosta plików wydań GitHuba oraz odrzucanie HTTP, danych logowania,
  niestandardowego portu i niezaufanych domen;
- celowane testy 1.6 objęły także wspólny launcher linków YouTube, trwały
  zapis jego preferencji, systemowy chooser, jawne alternatywy NewPipe i
  przeglądarki, fallbacki oraz URL filmu i kanału;
- długie przytrzymanie karty Historii/Powiadomień kopiuje wyłącznie
  zweryfikowany standardowy URL YouTube. Zwykłe kliknięcie nadal przechodzi
  przez wspólny launcher;
- po dodaniu SQLite Bundled i Ulubionych kompilacja aplikacji oraz androidTest
  przeszła, a 192 testy jednostkowe debug zakończyły się bez błędów. Na
  emulatorze Android przeszło 5 celowanych testów: pełna migracja wszystkich
  tabel schematu 22→24, migracja z niezatwierdzonym WAL, usunięcie
  przedwczesnego FTS/pola opisu ze schematu 23, INSERT+UPDATE przez androidowe
  `ContentValues` oraz kontrast tekstu co najmniej 4,5:1. Test `ContentValues`
  zabezpiecza regresję `UnsupportedOperationException` powodowaną wcześniej
  przez próbę konwersji `ContentValues.valueSet()` przez `toArray()`;
- katalog 49 twórców i 52 źródeł: brak pustych pozycji, zduplikowanych kluczy
  źródeł i adresów spoza dozwolonych hostów YouTube;
- ponownie scalone i przetworzone biblioteki natywne debug i release zawierają
  po 32 pliki i nie zawierają `libdatastore_shared_counter.so`;
- scalony manifest release nie zawiera zależności ani komponentów WorkManagera.
  Zawiera wyłącznie własny odbiornik dokładnego alarmu, odbiornik zdarzeń
  systemowych i niewyeksportowaną usługę pierwszoplanową `dataSync`;
- kod aplikacji, wygenerowane pliki pośrednie i lista zawartości APK nie
  zawierają klienta Piped ani adresów jego publicznych instancji;
- skan plików publikowanych: brak kluczy podpisu, APK/AAB, zrzutów pamięci,
  raportów awarii, osadzonych kluczy API oraz danych osobowych z certyfikatu.

### Artefakt wydania 1.5-beta

Podpisany artefakt `lewicowYT-1.5-beta.apk` ma:

- rozmiar 13 161 454 bajty (12,55 MiB);
- SHA-256 APK:
  `41bff61a26ddebf32f09064b4fa9e17a3b9a407b18c9d29316e98f3443995eac`;
- pakiet `pl.lewicowyt.notifier`, `versionCode 15`, `versionName 1.5-beta`;
- poprawny podpis APK Signature Scheme v2 i jednego sygnatariusza;
- SHA-256 certyfikatu:
  `2d242f3390a37913459085f86edf96484f2ccc3866e327325d2915c74a8e980a`;
- minimalny Android API 26, docelowy API 36 oraz cztery deklarowane ABI.

SHA-256 jest zgodny z identyfikatorem raportów VirusTotal i MobSF.

### Historyczny artefakt 1.4-beta

Poprzednio wygenerowany w Android Studio podpisany artefakt
`lewicowYT-1.4-beta.apk` został sprawdzony narzędziami Android SDK:

- SHA-256 APK:
  `ea534b9b2307c1d4f7dbdd079e4162479a72ec8f7ca480eed7c2806ac12a6938`;
- pakiet `pl.lewicowyt.notifier`, `versionCode 14`, `versionName 1.4-beta`;
- podpis APK Signature Scheme v2 jest poprawny, liczba sygnatariuszy: 1;
- SHA-256 certyfikatu:
  `2d242f3390a37913459085f86edf96484f2ccc3866e327325d2915c74a8e980a`;
- minimalny Android API 26, docelowy API 36 oraz cztery deklarowane ABI.

Skrót tego APK jest zgodny z identyfikatorem raportu VirusTotal 1.4-beta. Nie
wolno używać go jako skrótu ani raportu dla 1.5-beta.

## Niezawodność danych i harmonogramu

- odpowiedź RSS jest traktowana jako szybki początek, a nie dowód kompletności
  zakresu. Historia jest oznaczana jako ukończona dopiero po dojściu Data API
  albo YouTube Web do granicy czasu lub końca źródła;
- bez Data API nowe powiadomienia wynikają z różnicy stabilnych identyfikatorów
  pomiędzy kolejnymi poprawnymi odpowiedziami RSS. Zmiana tytułu, kolejności
  albo wypadnięcie najstarszego wpisu nie tworzy powiadomienia;
- dłuższa historia jest pobierana etapami po 14 dni, w kolejności Filmy,
  Shorty, Streamy, przy maksymalnie pięciu kanałach historii równolegle;
- paginacja YouTube traktuje samo echo ostatniego kursora jako wyczerpanie
  odpowiedzi, a cykle obejmujące różne wcześniejsze tokeny nadal zatrzymuje
  ochroną przed pętlą. Nie oznacza zakresu jako ukończonego po osiągnięciu
  limitu bezpieczeństwa ani nie przesuwa kursora powiadomień, dopóki nie
  obejmie luki od poprzedniego punktu;
- ręcznie sortowana playlista nie jest uznawana za chronologiczną: historia i
  wykrywanie powiadomień dochodzą do jej końca albo jawnego limitu
  bezpieczeństwa zamiast zatrzymywać się na pierwszym starszym materiale;
- zapis wpisu historii, jego pochodzenia, stanu sprawdzenia dla powiadomień oraz
  decyzji o kolejce jest atomowy, więc równoległy backfill nie może zgubić
  powiadomienia;
- źródła są porządkowane przez lokalny estymator aktywności przed wejściem do
  ograniczonej kolejki. Model ma 28-dniowe zapominanie, ograniczone wartości,
  stabilne rozstrzyganie remisów i osobny poziom `overdue`, który zapobiega
  zagłodzeniu rzadko publikujących kanałów. Historia korzysta z wyniku, ale nie
  uczy modelu na ponownie pobieranych starych stronach;
- rekord o niepewnym typie zachowuje silniejszą wcześniejszą klasyfikację.
  Dopiero brak rozstrzygającego dowodu używa odwracalnego fallbacku `VIDEO`,
  który późniejsza karta kanału lub stan transmisji może poprawić;
- wyłączone globalnie lub dla twórcy typy są filtrowane przed zapisem. Przy
  selektywnych ustawieniach niejednoznaczny wpis RSS/API czeka na potwierdzenie
  właściwą kartą, zamiast przedostać się do włączonej kategorii;
- harmonogram utrzymuje 15 regularnych alarmów `RTC_WAKEUP` z rozłącznymi,
  niemutowalnymi `PendingIntent`. Retry, watchdog i DND probe/catch-up używają
  osobnych requestCode, więc nie zastępują regularnej kolejki. Odbiornik
  uzupełnia 15 przyszłych terminów przed uruchomieniem sieci; boot, zmiana czasu
  i strefy odtwarzają harmonogram;
- czyszczenie historii wymaga potwierdzenia, anuluje aktywne pobieranie oraz
  dokładny alarm, czeka na wyłączną sekcję synchronizacji, usuwa historię,
  skrzynkę, identyfikatory i kursory w jednej transakcji, czyści cache obrazów,
  a następnie odtwarza harmonogram;
- pliki awarii JVM `hs_err_pid*.log`, `replay_pid*.log` i zrzuty `*.hprof` są
  ignorowane przez repozytorium i nie powinny być publikowane.
- przypomnienie o optymalizacji baterii opiera się na rzeczywistym stanie
  systemowym, jest ponawiane przy wejściu do ustawień tylko podczas ograniczania
  aplikacji i nie zapisuje fałszywej zgody na podstawie samego otwarcia ekranu.

## Warunki przed publicznym wydaniem

- utworzyć nowy klucz podpisu i nowe hasło offline. Dane certyfikatu APK są
  publiczne, a ten sam bezpiecznie zarchiwizowany klucz będzie wymagany do
  wszystkich późniejszych aktualizacji;
- potwierdzić prawo do redystrybucji początkowego doboru kanałów albo niezależnie
  odtworzyć katalog z publicznych stron YouTube;
- sprawdzić nazwę i ikonę względem aktualnych zasad marki YouTube. Kod nie może
  sam rozstrzygnąć zgody na znaki towarowe lub wizerunki użyte w grafice;
- przed tagiem przepuścić pierwszy commit przez przygotowany workflow GitHub
  Actions na Ubuntu;
- wykonać test instrumentacyjny kodeka JXL oraz próbę dokładnego alarmu,
  synchronizacji i powiadomienia przy zgaszonym ekranie oraz w Doze na
  prawdziwym urządzeniu. AlarmManager, start usługi pierwszoplanowej i natywny
  kodek zależą od systemu/OEM, więc testy JVM nie mogą tego zastąpić;
- po wygenerowaniu podpisanego APK sprawdzić jego certyfikat, identyfikator,
  wersję i zawartość według `RELEASES_GITHUB.md`.
