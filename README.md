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
- **microservice-user-collections** — the next hop of that same cascade. Having dropped the thread,
  this service announces `COMMENTS_DELETED` on `comments-events` naming every comment it took,
  because nobody else ever knew which comments hung under that meme. Choreography, not saga: no
  orchestrator and no compensation — but since round 10 the announcement **is** durable. The thread
  delete and the announcement's outbox row are one transaction (`comment_events_outbox`, V4), so a
  rollback takes the announcement with it and a crash between the commit and the send loses nothing:
  the row stays unpublished and the shared outbox library's republisher re-sends it, marking it only
  once the broker confirms. That durability used to be judged not worth its price here, and the
  judgement was about price: the mechanism is now a kernel library
  (`com.jrobertgardzinski:transactional-outbox`, extracted from microservice-memes), so owning it
  costs a migration and a config class. What tipped it is that the loss was unrepairable — an emptied
  thread (no comments, or a redelivered event) announces nothing at all, so nothing ever re-derived a
  lost announcement, and the only repair left was the UI's read-repair dropping a stale reference.
  Deleting a single comment does not announce anything either; only the cascade does.
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
PUT    /memes/{memeId}/comments/{commentId}/hidden { "hidden": ... }    MODERATOR/ADMIN only -> 200 | 403 | 404
```

A hidden comment stays in the thread as a tombstone: readers get `{hidden: true}` with
`text: null`, the author still sees their own words with the flag (the gentler counterpart
to deletion). It's a moderator's judgement, kept in a separate `comment_flags` table so a
deleted comment sheds it by cascade.

## Run & test

```bash
../mvnw -f pom.xml test    # unit + MockMvc black-box on the real JDBC adapters (H2)
```

In the compose stack: port 8085, own Postgres (`comments-postgres`).
