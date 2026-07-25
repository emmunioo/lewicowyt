# Audyt bezpieczeństwa

Data przeglądu: 25 lipca 2026 r.

## Zakres

Sprawdzono kod Kotlin, manifest i zasoby Androida, konfigurację Gradle, skrypty
startowe dla Windows i systemów uniksowych, katalog twórców, dokumentację oraz
integracje sieciowe YouTube, Piped i GitHub Releases.

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
- `SystemJobService` jest wymagany przez JobScheduler i chroniony systemowym
  `android.permission.BIND_JOB_SERVICE`. `ProfileInstallReceiver` służy
  narzędziom profilowania i jest chroniony `android.permission.DUMP`, którego
  zwykła aplikacja nie może uzyskać. Zbędny `DiagnosticsReceiver` WorkManagera
  został mimo to usunięty z wariantu release;
- raport powielił analizę bibliotek natywnych dla czterech ABI. Wymagane
  biblioteki JXL mają NX, kod pozycyjnie niezależny, stack canary, pełne RELRO,
  brak RPATH/RUNPATH i usunięte symbole. Nieużywany przez tę jednoprocesową
  aplikację `libdatastore_shared_counter.so`, który nie miał stack canary ani
  usuniętych symboli, został wyłączony z pakowania;
- `logcat` nie zawierał awarii, ANR ani `OutOfMemoryError`. Pokazał natomiast
  błąd HTTP 504 jednego kanału, niedostępność Piped i ponowienie całego zadania
  po częściowym powodzeniu;
- zrzut systemu potwierdził utworzenie kanału powiadomień oraz prawidłowe
  uruchomienie WorkManagera z wymaganiami sieci i opcjonalnie nie-niskiego
  poziomu baterii. Badanie nie obejmowało wiarygodnej próby z wygaszonym ekranem;
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
- wspólny klient HTTP nie wykonuje automatycznych przekierowań. Zapobiega to
  przekierowaniu żądania z przejętej instancji Piped do sieci lokalnej lub innego
  nieoczekiwanego hosta; jedyny obsługiwany ręcznie mechanizm przekierowań dotyczy
  obrazów i ponownie sprawdza dozwoloną domenę na każdym kroku;
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
- odpowiedzi Piped mają limity czasu i rozmiaru, ograniczoną długość pól oraz
  walidację poprawnego zagnieżdżenia JSON do głębokości 100;
- odpowiedzi YouTube Web, RSS i Data API mają limity rozmiaru, a tytuły,
  autorzy, klucze klienta oraz tokeny kontynuacji są ograniczane i walidowane
  przed dalszym użyciem;
- pobrane dane Piped nie dostarczają aplikacji gotowych odnośników do otwarcia:
  wykorzystywany jest tylko poprawny, 11-znakowy identyfikator filmu;
- wpis Piped jest traktowany jako niezaufany i nie trafia do kolejki powiadomień,
  dopóki YouTube RSS, YouTube Data API albo kontrolowana ścieżka YouTube Web nie
  zwróci tego materiału dla
  synchronizowanego kanału. Sprawdzenie samego istnienia i typu filmu nie zmienia
  jego pochodzenia na YouTube;
- dane Piped nie mogą przesunąć trwałego kursora powiadomień; punkt odniesienia
  aktualizują wyłącznie wpisy potwierdzone przez YouTube;
- kolejki nadchodzących transmisji i gotowych powiadomień filtrują rekordy po
  pochodzeniu `YOUTUBE`, co blokuje również stare wpisy Piped zapisane w bazie;
- nieprawidłowe i skrajnie przyszłe daty z RSS, Data API, YouTube Web lub Piped
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
  adresów ich kanałów ani stosów wyjątków;
- pojedynczy niedostępny kanał nie ponawia już całej synchronizacji dziesiątek
  poprawnie sprawdzonych źródeł. WorkManager ponawia awarię całkowitą albo
  obejmującą co najmniej połowę prób, najwyżej dwa razy.

## Ryzyka pozostające z założenia

- klucz YouTube Data API jest własnością użytkownika. Jest zaszyfrowany w pamięci
  aplikacji, maskowany w interfejsie i wyłączony z kopii zapasowej, ale proces
  działający z uprawnieniami roota może przejąć sekret podczas używania go przez
  aplikację;
- tryb bez klucza zależy od nieoficjalnego formatu strony YouTube oraz publicznych
  instancji Piped. Zmiany po stronie usług mogą powodować błędy dostępności, nie
  dają jednak tym usługom dostępu do lokalnej bazy;
- publiczna instancja Piped widzi adres IP urządzenia i identyfikatory sprawdzanych
  kanałów;
- AdGuard DNS widzi nazwy rozwiązywanych domen i adres IP urządzenia. Jeśli oba
  adresy startowe DoH są niedostępne, aplikacja przechodzi na pięć minut na resolver
  systemowy, co zachowuje dostępność kosztem chwilowego braku aplikacyjnego DoH;
- sprawdzanie aktualizacji otwiera stronę pobierania w przeglądarce. Ostateczną
  ochroną aktualizacji pozostaje weryfikacja podpisu APK przez Androida.
- Android oraz nakładka producenta mogą opóźnić WorkManager mimo poprawnego
  harmonogramu, szczególnie w Doze albo przy restrykcyjnym oszczędzaniu energii.
  Aplikacja nie może zagwarantować wykonania dokładnie co do minuty.

## Wynik automatycznej walidacji

Końcowy, czysty przebieg lokalny z 25 lipca 2026 r. zakończył się powodzeniem:

- 58 testów jednostkowych wariantu debug i 58 tych samych testów wariantu
  release: 0 niepowodzeń, 0 błędów i 0 pominięć;
- kompilacja Kotlin wariantów debug i release: powodzenie;
- Android Lint dla debug i release: 0 błędów. Pozostało 29 ostrzeżeń
  informacyjnych: dostępność nowszych, celowo jeszcze niewprowadzonych wersji
  zależności, sugestie zamiany jawnych transakcji na skróty KTX, kontrola
  najnowszego API oraz kształt pomocniczych ikon rastrowych;
- katalog 49 twórców i 52 źródeł: brak pustych pozycji, zduplikowanych kluczy
  źródeł i adresów spoza dozwolonych hostów YouTube;
- ponownie scalone i przetworzone biblioteki natywne debug i release zawierają
  po 32 pliki i nie zawierają `libdatastore_shared_counter.so`;
- scalony manifest release nie zawiera WorkManager `DiagnosticsReceiver`; poza
  aktywnością startową eksportowane pozostają wyłącznie komponenty AndroidX
  chronione uprawnieniami systemowymi;
- skan plików publikowanych: brak kluczy podpisu, APK/AAB, zrzutów pamięci,
  raportów awarii, osadzonych kluczy API oraz danych osobowych z certyfikatu.

Końcowy APK nie został wytworzony w tym przebiegu. Podpisany artefakt ma zostać
wygenerowany przez Android Studio dopiero po utworzeniu właściwego klucza
wydawcy.

## Niezawodność danych i harmonogramu

- prawidłowa, lecz pusta albo niepełna odpowiedź Piped nie kasuje błędu YouTube
  podczas doładowywania historii; zakres pozostaje częściowy i można go ponowić;
- paginacja YouTube wykrywa powtarzające się kursory i tokeny, nie oznacza
  zakresu jako ukończonego po osiągnięciu limitu bezpieczeństwa oraz nie
  przesuwa kursora powiadomień, dopóki nie obejmie luki od poprzedniego punktu;
- ręcznie sortowana playlista nie jest uznawana za chronologiczną: historia i
  wykrywanie powiadomień dochodzą do jej końca albo jawnego limitu
  bezpieczeństwa zamiast zatrzymywać się na pierwszym starszym materiale;
- zapis wpisu historii, jego pochodzenia, stanu sprawdzenia dla powiadomień oraz
  decyzji o kolejce jest atomowy, więc równoległy backfill nie może zgubić
  powiadomienia;
- rekord o nieznanym typie jest ponawiany z ograniczeniem prób, dzięki czemu
  pojedynczy trwale nieklasyfikowalny film nie blokuje całej kolejki;
- harmonogram dzienny używa jednej stałej nazwy unikalnej WorkManagera. Zadanie
  następującego dnia jest dopinane po terminalnym zakończeniu bieżącego, a zmiana
  ustawień zastępuje istniejący łańcuch;
- czyszczenie historii wymaga potwierdzenia, anuluje aktywne pobieranie oraz
  zadania WorkManagera, czeka na wyłączną sekcję synchronizacji, usuwa historię,
  skrzynkę, identyfikatory i kursory w jednej transakcji, czyści cache obrazów,
  a następnie odtwarza harmonogram;
- pliki awarii JVM `hs_err_pid*.log`, `replay_pid*.log` i zrzuty `*.hprof` są
  ignorowane przez repozytorium i nie powinny być publikowane.

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
- wykonać test instrumentacyjny kodeka JXL oraz próbę synchronizacji i
  powiadomienia przy zgaszonym ekranie na prawdziwym urządzeniu. WorkManager i
  natywny kodek zależą od systemu/OEM, więc testy JVM nie mogą tego zastąpić;
- po wygenerowaniu podpisanego APK sprawdzić jego certyfikat, identyfikator,
  wersję i zawartość według `RELEASES_GITHUB.md`.
