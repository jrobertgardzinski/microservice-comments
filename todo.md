# TODO — microservice-comments

Tylko otwarte rzeczy. Historia = git log.

## Zrobione (wydzielenie z microservice-memes)
- Wątki komentarzy + głosy na komentarze (lib `voting`), realny Postgres + Flyway (H2 w testach).
- Brama do security (introspekcja tokena), istnienie mema przez HEAD do memes.
- Saga usuwania konta: oś komentarzy (reguły DELETE|ANONYMIZE_AUTHOR|KEEP_POPULAR_ANONYMIZED:n,
  wybór z wizarda nadpisuje default), potwierdzenie na `comments-events`.
- Kaskada `MEME_DELETED` → wątek znika razem z memem.

## Otwarte
- ~~Cucumber + Allure jak w pozostałych~~ — ZROBIONE (2026-07-04): `comment-thread.feature`
  (5 scenariuszy po HTTP: komentarz zalogowanego, odmowa anonima, odmowa pod nieznanym memem,
  głosy-przełączniki ze score w listingu, kaskada MEME_DELETED — listener wołany wprost przez
  beana-ogłoszeniodawcę, broker to nie kontrakt; hook @Before resetuje wątek kaskadą).
- **Słownik `PurgeRule` zduplikowany** z memes (celowo — wspólny kontrakt tekstowy); rozważyć
  malutką libkę, jeśli urośnie trzeci konsument.
- **Deduplikacja konsumenta** — purge idempotentny, więc zbędna; przy nie-idempotentnych
  komendach dołożyć dedup po id.
- Paginacja/limity długości wątków; rate-limit.
