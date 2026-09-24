-- Comments and their votes; one vote per (comment, voter). Kept portable across Postgres (the
-- stack) and H2 in PostgreSQL mode (tests).
CREATE TABLE comments (
    id         VARCHAR(36)   PRIMARY KEY,
    meme_id    VARCHAR(36)   NOT NULL,
    author     VARCHAR(255)  NOT NULL,
    content    VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP     NOT NULL
);
CREATE INDEX idx_comments_meme ON comments (meme_id);
CREATE INDEX idx_comments_author ON comments (author);

CREATE TABLE comment_votes (
    comment_id VARCHAR(36)  NOT NULL,
    voter      VARCHAR(255) NOT NULL,
    direction  VARCHAR(4)   NOT NULL,
    PRIMARY KEY (comment_id, voter)
);
CREATE INDEX idx_comment_votes_voter ON comment_votes (voter);
