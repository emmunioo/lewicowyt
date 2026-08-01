# Audyt bezpieczeństwa

Data przeglądu kodu: 1 sierpnia 2026 r.

Wersja kodu: `1.5-beta` (`versionCode 15`).

Podpisany APK 1.5-beta, jego SHA-256, VirusTotal i ponowny raport MobSF nie są
jeszcze dostępne. Poniższe dane konkretnego APK 1.4-beta pozostają historycznym
punktem odniesienia i nie są dowodem właściwości przyszłego artefaktu 1.5-beta.

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
- tekstowy klient odrzuca odpowiedzi binarne dla ścieżek metadanych. Integracja
  YouTube Web nie uruchamia WebView ani nie pobiera CSS, skryptów lub obrazów
  strony;
- historia każdego źródła zapisuje najpierw małą odpowiedź RSS, a dopiero potem
  uruchamia stronicowanie Data API albo YouTube Web. Duplikaty są scalane po
  identyfikatorze filmu;
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
- bieżący schemat bazy ma wersję 22 i przechowuje m.in. stan kart kanału,
  model kolejności, metadane awatarów oraz stabilny snapshot RSS dla
  powiadomień. Migracje są wykonywane sekwencyjnie także przy aktualizacji
  bezpośrednio ze starszej instalacji;
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
  przyszłej wersji stabilnej.
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

Końcowy przebieg lokalny dla kodu `1.5-beta` obejmuje przebudowaną historię,
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
- harmonogram używa jednego jednorazowego alarmu `RTC_WAKEUP` o stałej
  tożsamości `PendingIntent`. Odbiornik zapisuje następny zwykły termin przed
  uruchomieniem sieci; ewentualne ponowienie zastępuje go tym samym alarmem,
  więc nie powstają równoległe łańcuchy. Boot, zmiana czasu i strefy odtwarzają
  termin;
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
