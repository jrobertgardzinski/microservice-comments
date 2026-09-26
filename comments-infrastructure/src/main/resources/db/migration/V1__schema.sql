-- The whole schema, in one file: nothing is deployed anywhere, so there is no history to replay.
-- A change edits this file; a running dev database is recreated (docker compose -p security down -v).

CREATE TABLE comments (
    id                    VARCHAR(36)   PRIMARY KEY,
    meme_id               VARCHAR(36)   NOT NULL,
    author                VARCHAR(255)  NOT NULL,      -- the address, on its way out
    author_id             UUID,                        -- the stable identity; null only for rows the backfill missed
    content               VARCHAR(2000) NOT NULL,
    created_at            TIMESTAMP     NOT NULL,
    -- the account-closure saga's reversible mark: out of every thread, destroyed by nothing but the closure
    status                VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    marked_for_erasure_at TIMESTAMP,
    CONSTRAINT ck_comments_status CHECK (status IN ('ACTIVE', 'PENDING_ERASURE')),
    CONSTRAINT ck_comments_erasure_mark CHECK ((status = 'PENDING_ERASURE') = (marked_for_erasure_at IS NOT NULL))
);
CREATE INDEX idx_comments_meme ON comments (meme_id);
CREATE INDEX idx_comments_author ON comments (author);
CREATE INDEX idx_comments_author_id ON comments (author_id);
CREATE INDEX idx_comments_pending_erasure ON comments (status, marked_for_erasure_at);

-- every public read goes through the view and never sees a marked comment
CREATE VIEW active_comments AS
    SELECT id, meme_id, author, author_id, content, created_at
    FROM comments
    WHERE status = 'ACTIVE';

CREATE TABLE comment_votes (
    comment_id VARCHAR(36)  NOT NULL,
    voter      VARCHAR(255) NOT NULL,
    direction  VARCHAR(4)   NOT NULL,
    PRIMARY KEY (comment_id, voter),
    CONSTRAINT fk_comment_votes_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE CASCADE
);
CREATE INDEX idx_comment_votes_voter ON comment_votes (voter);

CREATE TABLE comment_flags (
    comment_id VARCHAR(36) PRIMARY KEY REFERENCES comments (id) ON DELETE CASCADE,
    hidden     BOOLEAN NOT NULL
);

-- the transactional outbox for COMMENTS_DELETED and friends (transactional-outbox library)
create table comment_events_outbox (
    id              varchar(36) primary key,  -- also the payload's event id
    topic           varchar(64) not null,
    event_type      varchar(64) not null,
    event_key       varchar(64) not null,     -- partition key
    cid             varchar(64),              -- correlation id, stamped at announce time
    payload         text        not null,
    created_at      timestamp   not null,
    published_at    timestamp,
    attempts        int         not null default 0,
    next_attempt_at timestamp
);
create index idx_comment_events_outbox_pending on comment_events_outbox (published_at, created_at);
