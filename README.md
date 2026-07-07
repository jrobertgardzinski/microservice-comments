# microservice-comments

Comment threads under memes, extracted into their own microservice — with **real persistence**
(Postgres + Flyway; H2 stands in for tests) and voting from the shared **`voting` library**
(the bounded context: one-vote-per-voter toggle + tally). Spring Boot, hexagon-lite in a single
module (`domain` / `config` / `application` / `infrastructure` packages).

## Who it talks to

- **microservice-security** — writing requires signing in, reading is public (a presented
  token additionally personalises `myVote`). Two interchangeable authentication gates,
  switched by `security.verify` (env `SECURITY_VERIFY`):
  - `introspect` (default) — asks `GET /me` per request: sees logouts and role changes
    immediately, costs one HTTP call per write.
  - `offline` — verifies the access token's EdDSA signature against security's
    `/.well-known/jwks.json` (keys cached; an unknown `kid` refetches once, which also covers
    security restarting with fresh keys). No per-request call — the trade-off is revocation
    blindness until the token's `exp`.
- **microservice-memes** — meme existence checks (HEAD) so comments never attach to ghosts, and
  the `MEME_DELETED` events on `memes-events`: when a meme goes, this service drops its whole
  thread (eventually consistent, idempotent).
- **Kafka / the account-deletion saga** — `PURGE_USER_CONTENT` on `content-commands` purges the
  leaver's comments under this service's axis of the policy (`DELETE` | `ANONYMIZE_AUTHOR` |
  `KEEP_POPULAR_ANONYMIZED:<n>`; wizard override wins over the `PURGE_COMMENTS_POLICY` default);
  the confirmation goes back on `comments-events`. Votes the leaver cast are always retracted.

## Contract

```
GET    /memes/{memeId}/comments?page=&size=        -> 200 [ { id, author, text, score, myVote } ]   (size cap 100, default 50)
POST   /memes/{memeId}/comments                    { "text": ... }      -> 201 | 400 | 401 | 404 | 429
POST   /memes/{memeId}/comments/{commentId}/votes  { "direction": ... } -> 200 { score, myVote } | 401 | 404
DELETE /memes/{memeId}/comments/{commentId}        author their own; MODERATOR/ADMIN anyone's
```

## Run & test

```bash
../mvnw -f pom.xml test    # unit + MockMvc black-box on the real JDBC adapters (H2)
```

In the compose stack: port 8085, own Postgres (`comments-postgres`).
