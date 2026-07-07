# Playbook dla Opusa — microservice-comments

Spisany 2026-07-07 (sesja Fable). Ten serwis jest w najlepszej formie z trójki —
backlog realnie mały. Zadania od góry.

## Zasady pracy w tym repo

- Jeden moduł Spring Boot (`src/`), pakiety per warstwa
  (`...comments.domain/application/config/infrastructure`). Postgres + Flyway
  (H2 w testach na tych samych adapterach).
- Brama do security: `HttpSecurityAuthenticationGate` (/me) oraz
  `JwtSecurityAuthenticationGate` (offline po JWKS, commit `7609b7c`+`bc408b8`);
  `Caller{email,roles}`. Istnienie mema przez HEAD do memes.
- Testy: unit + `comment-thread.feature` (+ moderacja) po HTTP; Allure.
- Build: `../mvnw -q -pl microservice-comments -am package` z korzenia workspace
  albo `mvn test` w repo (wrapper workspace'owy). Smoke: `cd .. && ./infra-smoke.sh`.
- Commit: angielska jednolinijkowa obrazowa wiadomość + stopka Co-Authored-By.

---

## C1. Uzgodnij todo.md + udokumentuj offline gate (mały, zrób pierwszy)

1. `todo.md`: sekcja „Zrobione (cd.)" — dopisz offline JWT gate (`7609b7c`, `bc408b8`:
   weryfikacja podpisu Ed25519 po JWKS security zamiast wołania /me; kompromis:
   offline nie widzi logoutu do wygaśnięcia tokena — świadomie, jak w security/todo).
2. README: sekcja „Authentication gate" — jak przełączyć HTTP↔JWT (property/env,
   sprawdź w `application.properties` i konstrukcji beanów), kiedy który wybrać
   (offline = mniej ruchu i brak zależności od dostępności security na odczyt ról;
   /me = natychmiastowa widoczność logoutu/zmiany ról).
3. Dodaj linijkę w todo: `Plan pracy: docs/opus-playbook.md`.

## C2. Audyt parytetu bramy z memes (mały)

Memes i comments mają bliźniacze `JwtSecurityAuthenticationGate` — porównaj oba
(diff ręczny): walidacja iss/exp/jti, obsługa kid/rotacji JWKS, zachowanie przy
niedostępnym JWKS (fallback do /me? odmowa?). Ujednolić zachowanie i testy
(`JwtSecurityAuthenticationGateTest` jest w memes — jeśli w comments brak
odpowiednika, dopisz lustrzany). Rozjazd w semantyce = bug do naprawy po OBU stronach
(dwa commity, po jednym na repo).

## C3. Strażnik kontraktu PurgeRule (mały)

Słownik `PurgeRule` jest celowo zduplikowany z memes (wspólny kontrakt TEKSTOWY).
Zamiast liby (odroczona do trzeciego konsumenta — tak zostaje): dopisz test
kontraktowy `PurgeRuleContractTest`, który przybija literały
(`DELETE`, `ANONYMIZE_AUTHOR`, `KEEP_POPULAR_ANONYMIZED:n` — parsowanie i odrzucanie
śmieci) IDENTYCZNIE jak `PurgeRuleTest` w memes — komentarz w teście wskazuje
lustrzany plik po drugiej stronie. Dryf złapie się w CI zamiast na produkcji.

## C4. PROPOZYCJA — ukrywanie komentarza przez moderatora (WYMAGA ZGODY USERA)

Memes ma NSFW-blur; w comments moderator umie tylko KASOWAĆ. Symetryczny, miękki
środek: `PUT /memes/{memeId}/comments/{commentId}/hidden {true|false}` (MODERATOR/ADMIN),
ukryty komentarz w listingu jako tombstone `{"hidden":true}` bez treści (autor widzi
swój z adnotacją). Zakres: kolumna `hidden` (V-next), reguła w domenie, 2 scenariusze
Gherkin, odsłona w galerii (memes-ui — osobny commit w memes).
NIE IMPLEMENTUJ bez potwierdzenia — to nowa funkcja, nie backlog.

## C-obserwacje

- Dedup konsumenta Kafki: purge idempotentny — dedup zbędny; wróci przy pierwszej
  nie-idempotentnej komendzie (zostaw wpis w todo jak jest).
- Paginacja/limity/rate-limit — zrobione 2026-07-04, nic nie ruszaj.
