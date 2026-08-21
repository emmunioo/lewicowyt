# Prywatna diagnostyka — testy na prawdziwym urządzeniu

Ta checklista nie opisuje sposobu odblokowania panelu. Test wykonuj z aktywną
diagnostyką, a po każdym scenariuszu zapisz snapshot i eksport GZIP.

- [ ] Zwykła automatyczna synchronizacja kończy się wpisem `END` z tym samym `syncId`.
- [ ] Aktywne DND odkłada sprawdzenie, a jego wyłączenie uruchamia nadrabianie.
- [ ] Brak internetu zapisuje `NO_NETWORK` i oddzielny stan retry.
- [ ] Po odzyskaniu internetu retry kończy synchronizację bez naruszenia kolejki 15 alarmów.
- [ ] Restart telefonu odbudowuje kolejkę do `15/15`.
- [ ] Zmiana interwału usuwa starą kolejkę i tworzy nową `15/15`.
- [ ] Ręczna i pierwsza synchronizacja mają osobne `syncId` oraz właściwy `trigger`.
- [ ] Lifecycle FGS i WakeLock zawiera start, nabycie, zwolnienie i stop.
- [ ] Decyzje materiałów rozróżniają Inbox od powiadomienia systemowego.
- [ ] „Zapisz stan diagnostyczny teraz” nie uruchamia sieci ani synchronizacji.
- [ ] „Sprawdź bazę danych” pokazuje `Baza danych: OK` dla zdrowej bazy.
- [ ] Eksport jest poprawnym GZIP i nie zawiera kluczy, tokenów, cookies ani query URL.
- [ ] Aktualizator raportuje redirect, SHA-256, package ID, versionCode i podpis.
- [ ] Czyszczenie cache raportuje tylko podsumowanie, bez bajtów obrazów.
- [ ] Błąd pojedynczego źródła zawiera `creatorId`, `sourceType` oraz odpowiednio
  `channelId` albo `playlistId`, ale nie zawiera URL-a źródła ani jego treści.
- [ ] YouTube Data API `playlistItems` z HTTP 404 zapisuje
  `reason=API_PLAYLIST_ITEMS_NOT_FOUND`, `operation=API_PLAYLIST_ITEMS` i `httpStatus=404`.
- [ ] Brak listy kart kanału zapisuje `reason=CHANNEL_TABS_UNAVAILABLE` wraz z
  `creatorId` i `channelId`; późniejszy fallback nie gubi wspólnego `syncId`.
- [ ] Udane „Znajdź starszy” zapisuje `OLDER_SEARCH_SUCCESS`, liczbę wyników,
  `creatorId`, czas i transfer, ale nigdy treść wyszukiwanego tekstu.
- [ ] Awaria „Znajdź starszy” zapisuje `OLDER_SEARCH_FAILED` i stabilny
  `reasonCode`; potwierdzenie obcego kanału ma `OLDER_MATERIAL_CHANNEL_MISMATCH`.
- [ ] Każda próba pobrania opisu ma `DESCRIPTION_FETCH` z wynikiem `SAVED`,
  `EMPTY`, `SAVE_FAILED` albo `ERROR`, publicznym linkiem filmu i `creatorId`,
  ale bez treści opisu.
- [ ] Koniec partii opisów zawiera `DESCRIPTION_SUMMARY`, a koniec synchronizacji
  `NETWORK_USAGE` z bajtami ciał HTTP wysłanymi, pobranymi i łącznie.
- [ ] Ręczny snapshot zawiera `NETWORK_USAGE_PROCESS`; wartości dotyczą tylko
  bieżącego procesu i ciał HTTP, bez nagłówków, TLS oraz DNS.
