# lewicowYT 1.7-beta

Natywna aplikacja dla Androida do lokalnego obserwowania wybranych kanałów
YouTube. Porządkuje filmy, Shorty i transmisje, zachowuje historię oraz informuje
o nowych publikacjach bez konta w aplikacji, Firebase i własnego serwera.

Wersja 1.7-beta dodaje lokalną wyszukiwarkę FTS5, etapowe pobieranie
skompresowanych opisów oraz bezpieczne wyszukiwanie starszych materiałów do
Ulubionych. Rozszerza również zabezpieczenia procesu wydania i prywatność
powiadomień na ekranie blokady.

> lewicowYT jest niezależnym, nieoficjalnym projektem. Nie jest produktem ani
> oficjalnym klientem Google lub YouTube i nie jest przez nie wspierany.

## Możliwości

### Twórcy i ustawienia materiałów

- wybór obserwowanych twórców i lokalne wyszukiwanie na liście;
- osobne ustawienia Historii i powiadomień dla Filmów, Streamów oraz Shortów,
  globalnie i dla konkretnego twórcy;
- wyłączenie Historii danego typu wyłącza jego powiadomienia i zbędne
  pobieranie danych;
- kliknięcie kafelka rozwija ustawienia twórcy, checkbox zmienia obserwowanie,
  a długie przytrzymanie otwiera właściwy kanał;
- obsługa twórców posiadających kilka kanałów oraz playlist.

### Historia i Ulubione

- historia z zakresu 7, 14, 21, 30 albo 60 dni;
- oddzielne filtry Filmów, Streamów, Shortów i Ulubionych;
- inteligentne lokalne wyszukiwanie FTS5 po tytule, twórcy i pobranym opisie:
  rozpoznaje polskie znaki i częste odmiany, toleruje drobne literówki, pomija
  nieistotne spójniki, stopniowo rozszerza zapytanie i waży tytuł wyżej niż
  twórcę oraz opis — bez wysyłania zapytania użytkownika do sieci;
- etapowe pobieranie opisów przez `snippet.description` YouTube Data API, gdy
  aktywny jest klucz użytkownika, z automatycznym fallbackiem YouTube Web;
- stan zaplanowanej transmisji wygasa po nadejściu jej terminu, a niezależne
  znaczniki 🗓️ i 📓 mogą jednocześnie pokazać planowanie oraz pobrany opis;
- ręczne wyszukiwanie starszych materiałów obserwowanego twórcy przez YouTube
  Web, z obowiązkowym ponownym potwierdzeniem kanału i danych przed dodaniem;
- automatyczne doczytywanie podczas przewijania;
- etapowe pobieranie po 14 dni w kolejności Filmy → Shorty → Streamy;
- do pięciu kanałów historii sprawdzanych równolegle;
- zachowanie częściowych wyników i możliwość ponowienia po błędzie;
- gwiazdka Ulubionych chroni materiał i współdzieloną miniaturę przed
  automatycznym usunięciem;
- zwykłe kliknięcie otwiera materiał, a długie przytrzymanie kopiuje jego
  link do schowka;
- odznaczony twórca znika z widoku natychmiast, ale jego cache może zostać
  zachowany przez 7 dni.

### Powiadomienia i działanie w tle

- szybkie wykrywanie nowych publikacji przez różnicę poprawnych snapshotów
  YouTube RSS lub przez Data API, jeżeli użytkownik poda klucz;
- trwały punkt odniesienia dla każdego kanału chroni przed ponownym
  zgłaszaniem starych materiałów;
- do trzech nowych materiałów otrzymuje osobne powiadomienia; większa paczka
  tworzy jedno powiadomienie zbiorcze;
- każdy wykryty materiał trafia również osobno do sekcji Powiadomienia;
- dokładne alarmy Androida, kolejka przyszłych terminów, krótki WakeLock i
  watchdog zwiększają niezawodność przy wygaszonym ekranie;
- aplikacja wstrzymuje sieć podczas systemowego trybu Nie przeszkadzać i
  nadrabia zaległe sprawdzenie po jego zakończeniu;
- pierwsza synchronizacja uruchamia się automatycznie po pierwszym wyborze
  twórców.
- zanonimizowana publiczna wersja powiadomienia nie ujawnia tytułu, twórcy ani
  miniatury na ekranie blokady.

Android nadal może ograniczać pracę aplikacji. Dla największej niezawodności
należy przyznać dostęp do Alarmów i przypomnień oraz ustawić użycie baterii
na **Bez ograniczeń**. Interwał 15 minut może zużywać więcej energii,
szczególnie bez klucza API i przy wielu kanałach.

### Źródła danych

1. YouTube RSS szybko dostarcza zwykle około 15 najnowszych wpisów.
2. Opcjonalne YouTube Data API v3 przyspiesza dłuższą historię i dostarcza
   wiarygodne informacje o transmisjach.
3. Bez API aplikacja uzupełnia historię z ograniczonych odpowiedzi tekstowych
   YouTube Web, bez WebView, CSS, logo i obrazów strony.

Klucz API jest opcjonalny, sprawdzany przed zapisem i przechowywany z użyciem
Android Keystore. Nie trafia do logów ani interfejsowego stanu zapisywanego przez
Compose.

### Otwieranie linków

Jedno wspólne ustawienie dotyczy filmów, Shortów, streamów i kanałów:

- domyślna aplikacja systemowa;
- pytaj za każdym razem;
- oficjalny YouTube;
- ReVanced lub inny klient YouTube;
- NewPipe;
- przeglądarka;
- dowolna inna aplikacja wybrana z programów widocznych w launcherze Androida.

Brak wybranej aplikacji powoduje bezpieczny fallback do systemowego `ACTION_VIEW`.
Integracja z ReVanced, podobnymi klientami oraz NewPipe nie używa ich SDK ani
API i nie wymaga instalowania żadnej z tych aplikacji. Tryb systemowy respektuje
aplikację domyślną wybraną w ustawieniach Androida.
Opcja dowolnej aplikacji przekazuje jej standardowy URL jawnie; program, który
nie obsługuje takich adresów, może odmówić ich otwarcia, ale nie powoduje to
awarii lewicowYT.

### Wygląd i dostępność

- motyw systemowy, jasny i ciemny;
- dowolny kolor akcentu z dodatkową kopią awaryjną ustawienia;
- opcjonalny tryb wysokiego kontrastu;
- opisy i stany dla TalkBack, większe cele dotykowe oraz układ odporny na
  powiększony tekst;
- oddzielne akcje dostępności dla kafelka materiału i gwiazdki Ulubionych.

## Obrazy i pamięć

- miniatury Historii i Powiadomień korzystają ze wspólnego cache;
- SHA-256 zapobiega wielokrotnemu zapisaniu identycznego obrazu;
- po poprawnej konwersji cache może używać JXL quality 69 / effort 10;
- systemowe powiadomienia zachowują format bitmapowy obsługiwany przez Androida;
- awatary 176×176 są dołączone do APK i najwyżej raz w tygodniu sprawdzane
  pod kątem zmiany;
- zwykła historia jest czyszczona po 60 dniach, a wpisy sekcji Powiadomienia po
  14 dniach; Ulubione są chronione.

## Prywatność i bezpieczeństwo

- brak konta aplikacji, Firebase, telemetrii i własnego backendu;
- obserwowani twórcy, historia i ustawienia pozostają lokalnie;
- HTTPS oraz opcjonalny fallback DoH AdGuard, jeżeli Android nie ma własnego
  aktywnego Prywatnego DNS;
- brak dostępu do lokalizacji, kontaktów, mikrofonu i kamery;
- baza jest otwierana przez dołączony AndroidX SQLite; FTS5 indeksuje osobną
  reprezentację tekstową, a pełne opisy są przechowywane jako Zstd level 5 lub
  UTF-8, jeżeli kompresja nie daje oszczędności;
- prywatna diagnostyka jest domyślnie wyłączona, nie zapisuje sekretów,
  nagłówków autoryzacji ani pełnych odpowiedzi sieciowych.

Szczegóły: [SECURITY_AUDIT.md](SECURITY_AUDIT.md),
[DATA_SOURCE.md](DATA_SOURCE.md) i [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Aktualizacje

Aplikacja sprawdza publiczne GitHub Releases razem z synchronizacją, nie częściej
niż raz na 2 godziny. APK jest pobierany do prywatnego cache aplikacji. Obsługa
przekierowań GitHuba ma limit kroków i allowlistę hostów HTTPS. Przed otwarciem
systemowego instalatora aplikacja sprawdza dostępny SHA-256, limit rozmiaru,
`applicationId`, wersję i zgodność certyfikatu. Android zawsze wymaga końcowego
potwierdzenia instalacji.

Wydanie może opcjonalnie udostępniać mniejszą aktualizację Xdelta3/VCDIFF.
Aplikacja używa jej tylko dla dokładnie zgodnego bazowego APK i oszczędności co
najmniej 20%. Odtworzony plik musi mieć identyczny SHA-256 jak oficjalny pełny
APK i przechodzi te same kontrole pakietu, wersji oraz podpisu. Każdy brak,
błąd lub anulowanie delty powoduje cichy powrót do pełnego APK; pełny APK zawsze
pozostaje dostępny w GitHub Release. Generator wydania pobiera przypięte
Xdelta3 z limitem rozmiaru i kontrolowanymi przekierowaniami, weryfikuje SHA-256
ZIP oraz EXE, wykonuje rekonstrukcję kontrolną, a patch i ponownie sparsowany
manifest publikuje z plików tymczasowych dopiero po pełnej walidacji.

## Wymagania

- Android 8.0 lub nowszy (API 26+);
- połączenie z internetem;
- zgoda na powiadomienia na Androidzie 13+;
- zgoda na Alarmy i przypomnienia dla dokładnego harmonogramu;
- opcjonalnie darmowy klucz YouTube Data API v3.

## Budowanie

Projekt można otworzyć bezpośrednio w Android Studio. Do kompilacji służy
Gradle Wrapper dołączony do repozytorium.

Przygotowanie awatarów przed wydaniem uruchamia wyłącznie przypięte pliki
`cjxl.exe` i `yt-dlp.exe` o oczekiwanych sumach SHA-256. Ręczny ZIP źródeł
należy tworzyć skryptem `tools/New-SourceArchive.ps1` albo użyć automatycznego
archiwum taga GitHuba; nie należy pakować całego katalogu roboczego.

```powershell
.\gradlew.bat :app:assembleDebug
```

Podpisane wydanie przygotuj w Android Studio przez:

`Build → Generate Signed App Bundle or APK → APK → release`

Nie publikuj pliku keystore, haseł, `local.properties`, prywatnych logów ani
debugowego APK. Instrukcja wydania znajduje się w
[RELEASES_GITHUB.md](RELEASES_GITHUB.md).

## Licencja

Kod projektu jest udostępniany na warunkach
[Anti-Capitalist Software License 1.4](LICENSE). Licencje bibliotek i pozostałe
noty znajdują się w [LICENSES](LICENSES) oraz
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
