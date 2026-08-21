# Źródło listy twórców

`app/src/main/assets/creators.json` przygotowano z publicznych adresów kanałów
i playlist YouTube zebranych wcześniej w lokalnym arkuszu roboczym. Arkusz
nie jest częścią publikowanego repozytorium.

Początkowy dobór adresów korzystał z publicznego katalogu prowadzonego przez
niezależną stronę trzecią. Projekt nie jest właścicielem tej strony, nie jest
z nią powiązany i nie pobiera z niej danych podczas działania aplikacji.
Przed redystrybucją katalogu wydawca powinien potwierdzić prawo do jego
wykorzystania albo niezależnie odtworzyć dobór wyłącznie z publicznych stron
YouTube.

Przetwarzanie:

1. pominięto nagłówek arkusza;
2. zgrupowano powtarzające się nazwy twórców;
3. zachowano wiele kanałów należących do jednej osoby/projektu;
4. wpis playlisty „Program Polityczny” oznaczono jako `PLAYLIST`;
5. pominięto adres `youtube.com/redirect`, który prowadził do Facebooka;
6. adresy `http://` znormalizowano do `https://`.

Wynik: 49 twórców, 52 źródła YouTube.

## Dane materiałów w 1.7-beta

Podstawowa Historia nadal korzysta z RSS, opcjonalnego YouTube Data API oraz
YouTube Web. Dla aktywnego, zweryfikowanego klucza opisy są pobierane partiami
z `videos.list` i pola `snippet.description` YouTube Data API. Bez klucza albo
po błędzie lub pominięciu materiału przez API używana jest publiczna odpowiedź
odtwarzacza YouTube Web (`videoDetails.shortDescription`). Dotyczy to wyłącznie
materiałów już zapisanych lokalnie i odbywa się dopiero po ukończeniu
podstawowej partii Historii.

Zwykłe wyszukiwanie Historii działa lokalnie w SQLite FTS5. Tekst zapytania nie
jest wysyłany do sieci. Osobna funkcja „Znajdź starszy” wykonuje jawne żądanie
YouTube Web dla wybranego twórcy. Przed dodaniem wyniku do Ulubionych aplikacja
ponownie sprawdza identyfikator kanału, tytuł, datę i dostępność materiału.
