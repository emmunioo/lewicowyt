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
