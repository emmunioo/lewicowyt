# lewicowYT 1.0-beta

Natywna aplikacja dla Androida do lokalnego obserwowania wybranych kanałów
YouTube. Nie wymaga konta w aplikacji, Firebase ani własnego serwera.

> To niezależny, nieoficjalny projekt. Nie jest produktem ani oficjalnym klientem
> Google, YouTube lub Piped i nie jest przez nie wspierany.

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
- pokazuje przy wpisie źródło metadanych: `Piped` lub `YouTube`;
- używa miniatur 640×480 i kadruje je do proporcji 16:9 bez czarnych pasów.

### Powiadomienia

- sprawdza nowe materiały ręcznie albo okresowo przez WorkManager;
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
- nie pozwala, aby sam niezweryfikowany wpis Piped wywołał powiadomienie.
  Materiał musi zostać pobrany z RSS, YouTube Data API albo kontrolowanej
  ścieżki YouTube Web dla sprawdzanego źródła.

### Działanie w tle

- dostępne częstotliwości to od 15 minut do 12 godzin oraz raz dziennie;
- dla harmonogramu dziennego można wybrać lokalną godzinę;
- sprawdzanych jest do 6 źródeł równocześnie;
- wymaganie połączenia bez limitu i warunek odpowiedniego poziomu baterii można
  zmienić w ustawieniach;
- rozległe błędy sieci są ponawiane najwyżej dwa razy z wykładniczo rosnącym
  odstępem.

WorkManager działa również przy zgaszonym ekranie i po odtworzeniu harmonogramu
przez aplikację, ale Android nie gwarantuje wykonania dokładnie co do minuty.
Doze, brak sieci oraz dodatkowe ograniczenia oszczędzania energii producenta
telefonu mogą przesunąć synchronizację.

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

### Bez klucza: YouTube Web i Piped

Historia jest pobierana równocześnie ze strony YouTube i z publicznych instancji
Piped. Piped ma przyspieszać pierwsze wyniki i zapewniać dodatkową dostępność,
ale jest źródłem eksperymentalnym i mniej wiarygodnym niż YouTube.

- aplikacja próbuje do czterech skonfigurowanych instancji Piped;
- żądania mają krótkie limity czasu, ograniczenia rozmiaru odpowiedzi i
  zabezpieczenia przed zapętloną paginacją;
- wynik Piped jest oznaczony jako niezaufany, dopóki YouTube go nie potwierdzi;
- potwierdzone dane YouTube zastępują dane Piped;
- awaria Piped nie blokuje działającego źródła YouTube;
- publiczna instancja Piped widzi adres IP urządzenia i publiczne identyfikatory
  sprawdzanych kanałów, lecz nie otrzymuje lokalnej historii ani ustawień.

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

## Kompilowanie

Wymagane są:

- Android Studio z JDK 17 ustawionym jako Gradle JDK;
- Android SDK 36.1 oraz Build Tools 36.0.0;
- dostęp do internetu przy pierwszym pobieraniu zależności.

Kompilacja wariantu debug:

```powershell
.\gradlew.bat :app:assembleDebug
```

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
