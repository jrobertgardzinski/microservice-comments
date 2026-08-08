# TODO — microservice-comments

Tylko otwarte rzeczy. Historia = git log.

**Plan pracy z instrukcjami wykonawczymi: [docs/opus-playbook.md](docs/opus-playbook.md)**
(2026-07-07; C1–C4 ZROBIONE — playbook comments wyczerpany).

## Zrobione (wydzielenie z microservice-memes)
- Wątki komentarzy + głosy na komentarze (lib `voting`), realny Postgres + Flyway (H2 w testach).
- Brama do security (introspekcja tokena), istnienie mema przez HEAD do memes.
- Saga usuwania konta: oś komentarzy (reguły DELETE|ANONYMIZE_AUTHOR|KEEP_POPULAR_ANONYMIZED:n,
  wybór z wizarda nadpisuje default), potwierdzenie na `comments-events`.
- Kaskada `MEME_DELETED` → wątek znika razem z memem.
- **Drugi skok kaskady** (2026-07-26): po skasowaniu wątku serwis ogłasza `COMMENTS_DELETED`
  na `comments-events` (envelope v1: `id/type/memeId/commentIds/version`, klucz = `memeId`) —
  tylko ten serwis wie, KTÓRE komentarze wisiały pod memem. Bez outboxa (stawka = martwy ref,
  naprawi read-repair w UI), ale publikacja tylko po udanym commicie: `DeleteThread` zwraca
  listę id, ogłasza dopiero `MemesEventsListener` spoza transakcji. Pusty wątek = brak zdarzenia.
  **AWANS DO GWARANCJI OUTBOXA** (2026-07-26, paczka 10): argument „bez outboxa" był o CENIE, a
  cena spadła — mechanizm to teraz biblioteka jądra `com.jrobertgardzinski:transactional-outbox`
  (+ `infrastructure-spring-outbox`), wyciągnięta z utwardzonej implementacji memes, więc koszt
  posiadania to migracja `V4__comment_events_outbox.sql` i jedna klasa konfiguracji. Przeważyło to,
  że strata była NIENAPRAWIALNA: redostarczony `MEME_DELETED` trafia na pusty wątek i celowo nic
  nie ogłasza, więc zgubionego `COMMENTS_DELETED` nic nigdy nie odtworzy. Teraz kasowanie wątku i
  wiersz outboxa to JEDNA transakcja (otwiera ją `MemesEventsListener`, dekorator `DeleteThread`
  do niej dołącza), pierwsza próba zaparkowana na commicie i nieblokująca, marka `published`
  dopiero po potwierdzeniu brokera, republisher dosyła bajt-w-bajt to samo zdarzenie. Envelope v1,
  klucz `memeId`, pusty wątek = brak zdarzenia — BEZ ZMIAN. `id` koperty to teraz klucz wiersza
  (`OutboxEvent.newId()`), bo ścieżka republikacji ISTNIEJE i duplikat musi być rozpoznawalny.
  DOMKNIĘTE PRZY OKAZJI: zegary producenta, których ten serwis NIE MIAŁ —
  `max.block.ms=5000`/`delivery.timeout.ms=30000`/`request.timeout.ms=15000` + pin
  `KafkaProducerClocksTest`. Suita 81 → 91.

## Zrobione (cd.)
- **Offline JWT gate** — ZROBIONE (2026-07-06, `7609b7c`+`bc408b8`): `JwtSecurityAuthenticationGate`
  weryfikuje podpis EdDSA access tokena po JWKS security zamiast wołać `/me`; przełącznik
  `security.verify` = `introspect` (default) | `offline` (env `SECURITY_VERIFY`); kompromis
  świadomy — offline nie widzi logoutu/zmiany ról do `exp`. Opisane w README.
- **Moderacja komentarzy (MODERATOR)** — ZROBIONE (2026-07-04): brama czyta role z /me security
  (Caller{email,roles}), DELETE /memes/{memeId}/comments/{commentId} — autor swój, MODERATOR/ADMIN
  cudzy; DeleteComment autoryzuje (DELETED/FORBIDDEN/NO_SUCH_COMMENT) i kasuje komentarz+głosy.
  2 scenariusze Gherkin.

## Zrobione (cd.)
- **Ukrywanie komentarza przez moderatora (C4)** — ZROBIONE (2026-07-07, zgoda usera):
  miękki środek między niczym a kasowaniem — `PUT .../comments/{id}/hidden {hidden}`
  (MODERATOR/ADMIN, 403 NOT_A_MODERATOR), osobny store `CommentModeration` + tabela
  `comment_flags` (V2, FK cascade), listing pokazuje tombstone `{hidden:true, text:null}`
  czytelnikom a autorowi jego słowa z flagą (`CommentWithScore` niesie hidden+viewerIsAuthor),
  galeria: przycisk oka moderatora + tombstone. 2 scenariusze Gherkin. PRZY OKAZJI naprawiony
  leak połączeń: `hiddenIn`/`nsfwIds` używały `.query(...).stream()` (kursor otwarty) —
  przełączone na `.list()` (bliźniaczo w memes `JdbcContentFlags`).

## Otwarte
- **Kompensacja sagi offboardingu (ADR 0007) — WDROŻONE 2026-08-08.** Komenda czyszczenia
  **oznacza** treści (`PENDING_ERASURE` + `markedForErasureAt`), kasuje dopiero
  `ERASE_USER_CONTENT`, a `RESTORE_USER_CONTENT` cofa oznaczenie. Filtr `ACTIVE` jest w jednym
  miejscu — w widoku bazodanowym — a strażnik źródeł wywala build, gdy jakikolwiek SQL poza
  adapterem wymazywania nazwie tabelę bazową. Otwarte:
  - **Alarm zaległości nie ma reguły w Prometheusie** — jest gauge (`*_erasure_backlog`) i linia
    w logu, nie ma alertu. Zgubione domknięcie to problem RODO, więc powinien być.
  - (opc.) `pendingSince` używa tylko `StuckErasureWatch`; gdyby kiedyś przyszła polityka
    retencji, to jest miejsce, w którym się ją dopina — ale **nigdy** jako kasowanie z upływu czasu.

- ~~Cucumber + Allure jak w pozostałych~~ — ZROBIONE (2026-07-04): `comment-thread.feature`
  (5 scenariuszy po HTTP: komentarz zalogowanego, odmowa anonima, odmowa pod nieznanym memem,
  głosy-przełączniki ze score w listingu, kaskada MEME_DELETED — listener wołany wprost przez
  beana-ogłoszeniodawcę, broker to nie kontrakt; hook @Before resetuje wątek kaskadą).
- **Słownik `PurgeRule` zduplikowany** z memes (celowo — wspólny kontrakt tekstowy); rozważyć
  malutką libkę, jeśli urośnie trzeci konsument.
- **Deduplikacja konsumenta** — purge idempotentny, więc zbędna; przy nie-idempotentnych
  komendach dołożyć dedup po id.
- **PROPOZYCJA (NIE IMPLEMENTOWAĆ bez zgody) — `COMMENTS_DELETED` też przy pojedynczym kasowaniu.**
  Dziś tylko kaskada ogłasza; skasowanie jednego komentarza przez autora/moderatora zostawia
  w collections dokładnie taki sam martwy ref. Koperta już to unosi (`commentIds` z jednym id),
  więc kontrakt się nie zmienia — zmienia się miejsce publikacji: `DeleteComment` musiałby zwracać
  skasowane id, a ogłaszałby `CommentController` (analogicznie: spoza transakcji, po commicie),
  czyli publikacja wchodzi na ścieżkę HTTP. Koszt: dużo częstsze zdarzenia. Zysk: mniej pracy dla
  read-repair. Do decyzji razem z właścicielem collections.
- ~~Paginacja / limity długości wątków / rate-limit~~ — ZROBIONE (2026-07-04): listing
  stronicowany (`GET ...?page=&size=`, size cap 100, domyślnie 50; port findByMeme(offset,limit)
  + countByMeme; `ListComments.Page` z hasMore), limit długości komentarza w DOMENIE
  (`Comment.MAX_LENGTH=2000`, boundary → 400 COMMENT_TOO_LONG), rate-limit per-autor
  (`RateLimit` w config, env COMMENT_RATE_LIMIT, default 20/min, 429+Retry-After).
  Kontrakt GET wstecznie zgodny (płaska lista = strona 0). 2 nowe scenariusze Gherkin +
  RateLimitTest; wszystko zielone.
