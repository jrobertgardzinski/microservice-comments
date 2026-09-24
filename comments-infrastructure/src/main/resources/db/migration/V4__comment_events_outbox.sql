-- COMMENTS_DELETED gets the same durability the rest of the cascade has (round 10).
--
-- Until now this service announced the second cascade hop AFTER the thread delete committed and
-- only logged a failed send: a rollback never let an event out (good), but a crash — or a broker
-- outage — between that commit and the send lost the announcement forever, and nothing would ever
-- re-derive it: a redelivered MEME_DELETED finds an empty thread and deliberately announces
-- nothing. The only repair left was the UI noticing a dangling reference.
--
-- That trade was argued honestly and it was defensible while an outbox cost a table, a republisher,
-- a retention sweep and a migration PER SERVICE. It stopped being defensible when the mechanism
-- became a shared library (com.jrobertgardzinski:transactional-outbox, extracted from
-- microservice-memes' hardened implementation): the price dropped to this file plus a handful of
-- wiring lines, and at that price a lost event is no longer worth accepting.
--
-- The shape below is the library's own, copied from OutboxTable#ddl() — the library ships the
-- SHAPE, not the migration, because it has no right to a version number in this service's Flyway
-- sequence and two services may sit in one schema. Its tests create their table by executing that
-- very string, so what is documented cannot drift from what its queries expect. Column names are
-- fixed; only the table name is a parameter (see CommentsOutboxConfig).

-- Transactional outbox (com.jrobertgardzinski:transactional-outbox). Rows are
-- fully built, ready-to-send events written in the SAME transaction as the change
-- that announces them: a rollback takes the announcement with it, and a crash
-- between the commit and the send loses nothing — the republisher re-sends
-- whatever stayed unpublished. The row's id IS the event id inside the payload,
-- so a redelivery is a recognisable duplicate rather than a second event.
create table comment_events_outbox (
    id         varchar(36) primary key,  -- also the payload's event id
    topic      varchar(64) not null,
    event_type varchar(64) not null,
    event_key  varchar(64) not null,     -- partition key
    cid        varchar(64),              -- correlation id, stamped at announce time
    payload    text        not null,
    created_at timestamp   not null,
    published  boolean     not null default false
);

-- the republisher polls "unpublished and old enough" every few seconds, and reaps
-- "published and old enough" in the same pass — keep both off a table scan
create index idx_comment_events_outbox_pending on comment_events_outbox (published, created_at);
