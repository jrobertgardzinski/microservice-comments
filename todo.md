# TODO — microservice-comments

Tylko otwarte rzeczy. Historia = git log.

**Plan pracy z instrukcjami wykonawczymi: [docs/opus-playbook.md](docs/opus-playbook.md)**
(2026-07-07; C1 uzgodnienie todo+README bramy → C2 audyt parytetu JWT z memes →
C3 test kontraktowy PurgeRule → C4 tylko za zgodą usera).

## Zrobione (wydzielenie z microservice-memes)
- Wątki komentarzy + głosy na komentarze (lib `voting`), realny Postgres + Flyway (H2 w testach).
- Brama do security (introspekcja tokena), istnienie mema przez HEAD do memes.
- Saga usuwania konta: oś komentarzy (reguły DELETE|ANONYMIZE_AUTHOR|KEEP_POPULAR_ANONYMIZED:n,
  wybór z wizarda nadpisuje default), potwierdzenie na `comments-events`.
- Kaskada `MEME_DELETED` → wątek znika razem z memem.

## Zrobione (cd.)
- **Moderacja komentarzy (MODERATOR)** — ZROBIONE (2026-07-04): brama czyta role z /me security
  (Caller{email,roles}), DELETE /memes/{memeId}/comments/{commentId} — autor swój, MODERATOR/ADMIN
  cudzy; DeleteComment autoryzuje (DELETED/FORBIDDEN/NO_SUCH_COMMENT) i kasuje komentarz+głosy.
  2 scenariusze Gherkin.

## Otwarte
- ~~Cucumber + Allure jak w pozostałych~~ — ZROBIONE (2026-07-04): `comment-thread.feature`
  (5 scenariuszy po HTTP: komentarz zalogowanego, odmowa anonima, odmowa pod nieznanym memem,
  głosy-przełączniki ze score w listingu, kaskada MEME_DELETED — listener wołany wprost przez
  beana-ogłoszeniodawcę, broker to nie kontrakt; hook @Before resetuje wątek kaskadą).
- **Słownik `PurgeRule` zduplikowany** z memes (celowo — wspólny kontrakt tekstowy); rozważyć
  malutką libkę, jeśli urośnie trzeci konsument.
- **Deduplikacja konsumenta** — purge idempotentny, więc zbędna; przy nie-idempotentnych
  komendach dołożyć dedup po id.
- ~~Paginacja / limity długości wątków / rate-limit~~ — ZROBIONE (2026-07-04): listing
  stronicowany (`GET ...?page=&size=`, size cap 100, domyślnie 50; port findByMeme(offset,limit)
  + countByMeme; `ListComments.Page` z hasMore), limit długości komentarza w DOMENIE
  (`Comment.MAX_LENGTH=2000`, boundary → 400 COMMENT_TOO_LONG), rate-limit per-autor
  (`RateLimit` w config, env COMMENT_RATE_LIMIT, default 20/min, 429+Retry-After).
  Kontrakt GET wstecznie zgodny (płaska lista = strona 0). 2 nowe scenariusze Gherkin +
  RateLimitTest; wszystko zielone.
