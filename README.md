# lewicowYT 1.3-beta

Natywna aplikacja dla Androida do lokalnego obserwowania wybranych kanałów
YouTube. Nie wymaga konta w aplikacji, Firebase ani własnego serwera.

> To niezależny, nieoficjalny projekt. Nie jest produktem ani oficjalnym klientem
> Google ani YouTube i nie jest przez nie wspierany.

## Najważniejsze zmiany w 1.3-beta

- usunięto eksperymentalne źródło Piped. Najnowsze materiały są dostarczane
  lekką ścieżką YouTube, a starsza historia jest doczytywana bezpośrednio
  z YouTube Web albo opcjonalnego Data API;
- usunięto wybór trybów oszczędzania i zwiększonej niezawodności. Aplikacja ma
  teraz jeden mechanizm działania w tle nastawiony na pewniejsze powiadomienia
  również przy wygaszonym ekranie;
- nowy mechanizm może zużyć nieco więcej energii. Przy typowych ustawieniach
  różnica powinna być niewielka, ale interwał 15 minut i duża liczba
  obserwowanych kanałów mogą być zauważalne;
- klucz YouTube Data API może przyspieszyć pobieranie dłuższej historii, ale
  nie jest przedstawiany jako gwarantowany sposób zmniejszenia zużycia baterii;
- aplikacja sprawdza rzeczywisty stan ograniczeń baterii Androida i prowadzi
  bezpośrednio do ustawienia dotyczącego lewicowYT;
- po aktualizacji istniejącej instalacji najważniejsze zmiany są pokazywane
  jednorazowo w aplikacji.

## Co potrafi aplikacja

### Twórcy

- pozwala wybrać tylko tych twórców, których użytkownik chce obserwować;
- umożliwia wyszukiwanie na liście twórców oraz zaznaczenie lub odznaczenie
  wszystkich pozycji;
- kliknięcie kafelka zmienia stan obserwowania, a długie przytrzymanie całego
  kafelka otwiera kanał twórcy;
- obsługuje twórców posiadających więcej niż jeden kanał oraz źródła będące
  playlistami.

### Historia materiałów

- pokazuje filmy, Shorty, transmisje na żywo, zaplanowane transmisje i ich
  archiwalne zapisy;
- filtruje materiały według typu oraz zakresu: 7, 14, 21, 30 albo 60 dni;
- wyświetla wyłącznie aktualnie zaznaczonych twórców;
- automatycznie pobiera i pokazuje kolejne pozycje podczas przewijania, również
  wtedy, gdy pierwsza porcja nie wypełnia ekranu;
- pobiera wiele kanałów równolegle; chronologiczne karty kończy po dojściu do
  początku wybranego zakresu, a ręcznie sortowane playlisty sprawdza do końca
  (z limitem bezpieczeństwa), aby nie pominąć nowszego wpisu umieszczonego dalej;
- odczytuje i wyświetla do 10 000 najnowszych rekordów z 60-dniowego okna, zamiast ucinać
  dłuższą historię po kilku stronach;
- zachowuje lokalne dane odznaczonego twórcy przez 7 dni. Ponowne zaznaczenie
  w tym czasie przywraca je bez ponownego pobierania;
- dla każdego źródła najpierw zapisuje lekką odpowiedź RSS — zwykle około
  15 najnowszych pozycji — a następnie uzupełnia starszy zakres przez Data API
  albo YouTube Web;
- używa miniatur 640×480 i kadruje je do proporcji 16:9 bez czarnych pasów.

### Powiadomienia

- sprawdza nowe materiały ręcznie albo przez dokładny alarm systemowy;
- utrzymuje osobny, trwały punkt odniesienia dla każdego źródła, dzięki czemu
  restart lub aktualizacja aplikacji nie zgłasza ponownie całej historii;
- przy maksymalnie 3 nowych materiałach tworzy osobne powiadomienie dla każdego,
  z bezpośrednim odnośnikiem do filmu i najlepszą dostępną miniaturą do 720p;
- przy większej paczce tworzy jedno powiadomienie zbiorcze prowadzące do sekcji
  „Powiadomienia”;
- zapisuje wszystkie wykryte pozycje — także z małych paczek — w 14-dniowej
  sekcji „Powiadomienia”, od najnowszej;
- oznacza materiał jako dostarczony dopiero po przyjęciu powiadomienia przez
  Androida. Brak uprawnienia albo błąd publikacji pozostawia go do ponowienia;
- materiał musi zostać pobrany z RSS, YouTube Data API albo kontrolowanej
  ścieżki YouTube Web dla sprawdzanego źródła;

### Działanie w tle

- dostępne częstotliwości to od 15 minut do 12 godzin oraz raz dziennie;
- dla harmonogramu dziennego można wybrać lokalną godzinę;
- jedynym harmonogramem jest jednorazowy `AlarmManager.setExactAndAllowWhileIdle`
  z `RTC_WAKEUP`; po każdym wywołaniu aplikacja zapisuje następny termin;
- alarm uruchamia krótką usługę pierwszoplanową typu `dataSync`, więc pobieranie
  nie zależy od utrzymania procesu aplikacji ani okresowego WorkManagera;
- Android 12 i nowszy wymaga przyznania aplikacji systemowego dostępu
  „Alarmy i przypomnienia”; bez niego automatyczne sprawdzanie jest wyłączone;
- sprawdzanych jest do 6 źródeł równocześnie;
- ustawienie danych komórkowych obowiązuje każde automatyczne sprawdzenie;
- przed synchronizacją aplikacja wymaga sieci ze zweryfikowanym przez
  Androida dostępem do internetu;
- aplikacja sprawdza rzeczywisty stan optymalizacji baterii przez Androida
  i pokazuje, czy lewicowYT działa w trybie „Bez ograniczeń”;
- przy każdym wejściu do ustawień przypomina o trybie „Bez ograniczeń”, dopóki
  system nadal ogranicza aplikację. Przycisk otwiera systemowe żądanie dotyczące
  bezpośrednio lewicowYT, z awaryjnym przejściem do szczegółów aplikacji;
- brak dozwolonej sieci lub rozległy błąd jest ponawiany najwyżej dwa razy,
  co 15 minut;
- interwał 15 minut bez aktywnego klucza YouTube Data API jest oznaczony jako
  intensywny: sam RSS jest lekki, ale częste wybudzenia oraz ewentualne
  uzupełnianie przez YouTube Web mogą zwiększać transfer i zużycie energii.

Dokładny alarm może obudzić urządzenie także w Doze i przy wygaszonym ekranie.
Automatyczne sprawdzanie nie zadziała po użyciu funkcji „Wymuś zatrzymanie”,
bez specjalnego dostępu do alarmów, bez dozwolonej sieci albo jeśli producent
telefonu dodatkowo blokuje uruchamianie usług w tle.

### Aktualizacje

- aplikacja może ręcznie sprawdzić publiczne wydania GitHub bez Firebase i
  własnego serwera;
- wykrywa kolejne wydania beta, wersje RC oraz późniejsze wydania stabilne;
- pokazuje numer wersji, informacje o wydaniu, nazwę APK i udostępniony przez
  GitHub skrót SHA-256;
- akceptuje odnośniki do APK wyłącznie z właściwego repozytorium GitHub;
- pobieranie odbywa się w przeglądarce, a instalacja zawsze wymaga decyzji
  użytkownika;
- po aktualizacji istniejącej instalacji jednorazowo pokazuje lokalne okno
  „Co nowego”. Nie wyświetla go po pierwszej instalacji ani ponownie po
  potwierdzeniu.

### Wygląd i pamięć obrazów

- motyw systemowy, jasny albo ciemny;
- dowolny kolor akcentu RGB; domyślny kolor to czerwony `#FF0000`;
- miniatury i zdjęcia profilowe są dostępne natychmiast po pobraniu JPG, a
  następnie kompresowane w tle do JPEG XL;
- kompresja JXL używa jakości `69` i effort `10` (`GLACIER`);
- JPG jest usuwany dopiero po zapisaniu i zweryfikowaniu pliku JXL;
- obrazy powiadomień są jednorazowymi JPG, nie trafiają do cache JXL.

## Źródła danych

### Z opcjonalnym kluczem YouTube Data API

YouTube Data API v3 pobiera historię partiami, rozpoznaje rodzaje materiałów
i uczestniczy w wykrywaniu powiadomień. RSS YouTube pozostaje dodatkowym źródłem
dla najnowszych publikacji.

Klucz można uzyskać bezpłatnie w Google Cloud:

1. utwórz projekt;
2. włącz **YouTube Data API v3**;
3. utwórz klucz i ogranicz go do tej usługi; opcjonalnie możesz też ograniczyć
   go do aplikacji Android `pl.lewicowyt.notifier` i odcisku SHA-1 certyfikatu APK;
4. w aplikacji przejdź do `Ustawienia → Szybka historia`, wklej klucz i wybierz
   `Zweryfikuj i zapisz klucz`. Klucz zostanie aktywowany dopiero po poprawnej
   odpowiedzi kontrolnej YouTube.

[Jak uzyskać darmowy klucz API – wideo poradnik](https://youtu.be/EPeDTRNKAVo)

Klucz jest szyfrowany AES-256-GCM. Klucz szyfrujący pozostaje w Android Keystore,
a sekret nie trafia do zapisywalnego stanu interfejsu ani kopii zapasowej.

### Bez klucza: RSS i YouTube Web

Dla każdego kanału lub playlisty aplikacja najpierw pobiera mały kanał RSS
i natychmiast zapisuje zwrócone pozycje. RSS zwykle obejmuje około 15
najnowszych materiałów. Następnie YouTube Web uzupełnia starsze strony aż do
osiągnięcia wybranego zakresu czasu. Duplikaty między RSS i Web są scalane
lokalnie po identyfikatorze filmu.

Integracja Piped została usunięta. Aplikacja nie łączy się z publicznymi
instancjami Piped i nie przekazuje im identyfikatorów obserwowanych kanałów.

## Prywatność i retencja

- lista obserwowanych twórców, historia i ustawienia pozostają na urządzeniu;
- aplikacja nie ma dostępu do lokalizacji, kontaktów, mikrofonu, kamery ani
  plików użytkownika;
- kopia zapasowa danych aplikacji jest wyłączona;
- połączenia są wykonywane wyłącznie przez HTTPS;
- gdy Android nie ma aktywnego własnego Prywatnego DNS, aplikacja używa AdGuard
  DNS-over-HTTPS przez `https://dns.adguard-dns.com/dns-query`, z adresami
  startowymi `94.140.14.14` i `94.140.15.15`;
- aktywny Prywatny DNS Androida ma pierwszeństwo; awaria obu połączeń DoH
  powoduje czasowy fallback do resolvera systemowego;
- rekordy historii starsze niż 60 dni, wpisy sekcji „Powiadomienia” starsze niż
  14 dni oraz osierocone identyfikatory powiadomień są automatycznie usuwane.

Szczegóły modelu zaufania i zabezpieczeń opisuje
[SECURITY_AUDIT.md](SECURITY_AUDIT.md).

## Instalacja

Pobierz podpisany plik APK z zakładki **Releases** repozytorium. Jeżeli Android
o to poprosi, jednorazowo zezwól przeglądarce lub menedżerowi plików na
instalowanie aplikacji z tego źródła.

Aktualizacja zostanie zaakceptowana tylko wtedy, gdy ma ten sam
`applicationId`, wyższy `versionCode` i została podpisana tym samym kluczem co
poprzednia publiczna wersja.

Po pierwszym uruchomieniu zaktualizowanej aplikacji pojawi się jednorazowe
podsumowanie najważniejszych zmian w danym wydaniu.

## Kompilowanie

Wymagane są:

- Android Studio z JDK 17 ustawionym jako Gradle JDK;
- Android SDK 36.1 oraz Build Tools 36.0.0;
- dostęp do internetu przy pierwszym pobieraniu zależności.

Kompilacja wariantu debug:

```powershell
.\gradlew.bat :app:assembleDebug
```

Odtwarzalne wyniki Gradle są zapisywane poza katalogiem źródeł, w sąsiednim
katalogu `KOMPILACJA`. Debugowy APK znajdzie się w
`../KOMPILACJA/app/outputs/apk/debug/`.

Instalacja debug na podłączonym urządzeniu lub emulatorze:

```powershell
.\gradlew.bat :app:installDebug
```

Podpisany APK release należy wygenerować w Android Studio przez:

```text
Build → Generate Signed App Bundle or APK → APK
```

Instrukcja podpisywania i publikowania znajduje się w
[RELEASES_GITHUB.md](RELEASES_GITHUB.md).

## Automatyczne kontrole

GitHub Actions uruchamia testy jednostkowe, Android Lint oraz kompilację
wariantów debug i release dla każdego push oraz pull requestu. Weryfikacja
zależności Gradle i suma SHA-256 dystrybucji Wrappera ograniczają ryzyko
podmiany artefaktów kompilacji.

## Identyfikatory

```text
applicationId / namespace: pl.lewicowyt.notifier
baza danych: lewicowyt_notifier.db
DataStore: lewicowyt_settings
```

## Katalog twórców

Pochodzenie i sposób przygotowania listy opisuje
[DATA_SOURCE.md](DATA_SOURCE.md). Nazwy kanałów, znaki towarowe i materiały
udostępniane przez obserwowanych twórców należą do ich właścicieli.

## Licencja

Oryginalny kod i dokumentacja projektu są udostępniane na warunkach
[Anti-Capitalist Software License 1.4](LICENSE). Biblioteki dołączone do
aplikacji zachowują własne licencje opisane w
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
