# microservice-comments

Comment threads under memes, extracted into their own microservice — with **real persistence**
(Postgres + Flyway; H2 stands in for tests) and voting from the shared **`voting` library**
(the bounded context: one-vote-per-voter toggle + tally). Spring Boot, hexagon-lite in a single
module (`domain` / `config` / `application` / `infrastructure` packages).

## Who it talks to

- **microservice-security** — token introspection (`GET /me`): writing requires signing in,
  reading is public (a presented token additionally personalises `myVote`).
- **microservice-memes** — meme existence checks (HEAD) so comments never attach to ghosts, and
  the `MEME_DELETED` events on `memes-events`: when a meme goes, this service drops its whole
  thread (eventually consistent, idempotent).
- **Kafka / the account-deletion saga** — `PURGE_USER_CONTENT` on `content-commands` purges the
  leaver's comments under this service's axis of the policy (`DELETE` | `ANONYMIZE_AUTHOR` |
  `KEEP_POPULAR_ANONYMIZED:<n>`; wizard override wins over the `PURGE_COMMENTS_POLICY` default);
  the confirmation goes back on `comments-events`. Votes the leaver cast are always retracted.

## Contract

```
GET  /memes/{memeId}/comments                    -> 200 [ { id, author, text, score, myVote } ]
POST /memes/{memeId}/comments                    { "text": ... }        -> 201 | 400 | 401 | 404
POST /memes/{memeId}/comments/{commentId}/votes  { "direction": ... }   -> 200 { score, myVote } | 401 | 404
```

## Run & test

```bash
../mvnw -f pom.xml test    # unit + MockMvc black-box on the real JDBC adapters (H2)
```

In the compose stack: port 8085, own Postgres (`comments-postgres`).
