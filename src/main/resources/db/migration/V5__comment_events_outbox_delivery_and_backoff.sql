-- Three columns the outbox library grew on 2026-07-28, from PLAN-P13 (findings 08, 34). The shape
-- below is the library's own — see OutboxTable#ddl(), which its tests execute verbatim, so what is
-- documented cannot drift from what its queries expect.
--
-- WHY published_at REPLACES published. The boolean answered "is this row still owed?" perfectly
-- well, and NULL answers it identically. What it could not answer is "when did we send it?", so
-- retention measured its window from created_at instead — which meant a row that lay unpublished
-- longer than the window (a broker outage over a day, or an event that could not be sent until
-- someone fixed something) was ALREADY past the window the instant it was delivered. The very next
-- reaper pass, fifteen seconds later, deleted it. Exactly after a mass outage, when "what did we
-- really send, and when?" is the most valuable question in the building, the forensic trail lived
-- for one interval instead of a day.
--
-- WHY attempts AND next_attempt_at. An event that can never be sent — a payload past the broker's
-- max.request.size, a deleted topic, an ACL error — was retried every fifteen seconds for ever.
-- Worse: the poll is ORDER BY created_at, so undeliverable rows sat at the HEAD of the batch.
-- Enough of them (the batch is 500) and no newer event was ever selected again — the library's
-- "will eventually reach the broker" promise died silently, with a green test suite. The backoff
-- moves poison off the head of the queue; the attempt count eventually takes it out of the poll
-- entirely (it is never deleted — an undelivered obligation stays for a human to judge).
-- The index leads with the delivery column, so it has to go BEFORE the column it references —
-- dropping the column first fails with "may be referenced by idx_comment_events_outbox_pending".
drop index if exists idx_comment_events_outbox_pending;

alter table comment_events_outbox add column published_at timestamp;

-- Best available truth for rows already delivered: we know THAT they were sent, not when, and
-- created_at is the closest instant we recorded. It is also conservative for retention — it can
-- only make an old row reapable sooner, never keep an obligation alive by mistake.
update comment_events_outbox set published_at = created_at where published = true;

alter table comment_events_outbox drop column published;
alter table comment_events_outbox add column attempts int not null default 0;
alter table comment_events_outbox add column next_attempt_at timestamp;

-- rebuilt on the new predicate: the poll is "unsent and old enough", the reaper "sent and old
-- enough", and both lead with the delivery column
create index idx_comment_events_outbox_pending on comment_events_outbox (published_at, created_at);
