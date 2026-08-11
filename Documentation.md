# microservice-comments — Test Report & Documentation

Generated from Allure results by `build_documentation.py` on 2026-08-11. Behaviors below are **verified by passing tests** — rerun the suite, rerun this script, and the document cannot drift from the code.

## 📊 Execution Summary

| Module | Total | Passed | Failed | Broken | Skipped | Duration |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| microservice-comments | 157 | 157 | 0 | 0 | 0 | 15.13s |

## 📝 Test Documentation (Behaviors)

This section describes the verified system behaviors based on passing tests.

### Epic: Config

#### Feature: Purge rules

- **popularity decides what a rule keeps**
- **speaks the shared vocabulary: DELETE, ANONYMIZE_AUTHOR, KEEP_POPULAR_ANONYMIZED:n**

#### Feature: Rate limit

- **a new minute lifts the ceiling: the window expires instead of banning forever**
- **the ceiling is per key: one noisy account is capped, another is free**
- **the sweep evicts expired windows — the map holds recent commenters, not everyone ever seen**
- **zero disables the guard**

### Epic: Contract

#### Feature: Comments-deleted announcement

- **theAnnouncementShapeAndTopicTheCascadeReliesOn(PactVerificationContext) microservice-user-collections - a comments deleted announcement**

#### Feature: Meme-deleted announcement

- **the announcement drops that meme's whole thread**

#### Feature: Pact skip guard

- **the cascade pact is where CommentsDeletedPactProviderTest looks for it**
- **the saga pact is where PurgeConfirmationPactProviderTest looks for it**

#### Feature: Purge commands

- **erasesOnTheClosureAndAppliesTheRule(List)**
- **purgesWithTheDeploymentDefault(List)**
- **purgesWithTheLeaversChoice(List)**
- **restoresOnTheCompensation(List)**

#### Feature: Purge confirmation

- **theConfirmationShapeTheOrchestratorReliesOn(PactVerificationContext) microservice-offboarding - a user content purged confirmation**

### Epic: Domain

#### Feature: Erasure lifecycle

- **a fresh comment is ACTIVE and carries no mark**
- **a mark without its instant — or an instant without its mark — cannot be built**
- **a redelivered mark keeps the FIRST instant — the backlog measures an age**
- **marking reserves the comment, records when, and moves nothing else**
- **restoring a comment nobody marked is a no-op, not an error**
- **restoring puts it back exactly as it was, and twice is once**

### Epic: Executable specs

#### Feature: A deleted MEME takes its THREAD along

- **A MEME nobody commented on is announced to nobody**
- **The THREAD goes with the MEME, and the cascade passes the baton on**

#### Feature: Adding a COMMENT

- **A COMMENT is a remark, not an essay**
- **A COMMENT needs a real MEME to hang under**
- **A GUEST may read, not write**
- **A USER comments under a MEME that exists**

#### Feature: Deleting a COMMENT

- **A MODERATOR takes down anyone's COMMENT**
- **The author takes their own COMMENT down; a stranger cannot**

#### Feature: Hiding a COMMENT

- **Hidden for a GUEST, never for the author — and reversible**
- **Hiding is a MODERATOR's call**
- **Hiding needs an unambiguous decision**

#### Feature: Reading a THREAD

- **A long THREAD is read one page at a time — every COMMENT exactly once**
- **Behind the mask, the author still recognises their own words**
- **The THREAD survives the VOTE count going missing**
- **The listing signs a COMMENT with a masked name**

#### Feature: Voting on a COMMENT

- **One USER, one VOTE — and repeating the VOTE retracts it**

### Epic: Infrastructure

#### Feature: CORS origins

- **and an ingress origin is NOT — which is exactly what broke the cluster**
- **compose's gallery origin is allowed, so a local run is unaffected**
- **every origin in the comma-separated list is allowed, not merely the first**
- **every origin the k8s manifest sets is an origin the deployed-list case proves works**

#### Feature: Cascade topic names

- **the cascade's inbound listener is configured for the topic memes publishes on**
- **the cascade's outbound announcement goes to the topic collections subscribes to**

#### Feature: Comments HTTP API

- **comments_and_votes_full_circle()**

##### Story: Rate-limited commenting

- **the second comment in the window is refused with 429, Retry-After and a named status**

#### Feature: Health probes

##### Story: Heartbeat wiring

- **and that interceptor stamps the marker under the container's own id**
- **every registered container carries the record heartbeat**
- **the polling loop's heartbeat reaches the lamp — the registered id and the reporting one are not the same string**

##### Story: Listener lamp

- **a container Spring Kafka stopped is DOWN, and the details say it died abnormally**
- **a deployment without a broker is UP and says so — it takes no part in the saga**
- **a loop consuming without pause is UP — records are proof of life, not just empty polls**
- **a loop that stopped completing polls is DOWN — the stall the audit called invisible**
- **a running loop that keeps polling is UP — an idle topic is not a dead one**
- **a service still booting is not born unhealthy**
- **but a container that never reports at all goes DOWN once the tolerance is spent**
- **but a loop wedged inside one record is still DOWN — delivery, not arrival, is the beat**
- **no listener containers where listeners are expected is DOWN: commands reach nobody**

##### Story: Probe URLs

- **/actuator/health/liveness answers, and the lamp is deliberately NOT in it**
- **/actuator/health/readiness answers, and the listener lamp is in what it answers**
- **and the bare /actuator/health the compose stack probes still answers too**
- **the SHIPPED properties expose health, and the lamp's details with it**
- **the manifest probes the port the shipped properties actually put the actuator on**

##### Story: Probe group placement

- **and those property names really are the ones Spring builds the groups from**
- **the shipped configuration puts the listener lamp in readiness and NOT in liveness**

#### Feature: JDBC persistence

- **V3: deleting a comment cascades to its votes at the database level**
- **a cast losing the MERGE insert race retries once and lands as WHEN MATCHED**
- **a duplicate key on the cast RETRY is a bug and stays a raw error, not a fake 404**
- **a duplicate key on the hide RETRY is a bug and stays a raw error, not a fake 404**
- **cast is an upsert: two casts by one voter are one row, the last direction wins**
- **casting on a comment deleted mid-vote surfaces as UnknownComment, not a raw FK error**
- **hiding a comment deleted mid-hide surfaces as UnknownComment, not a raw FK error**
- **hiding that loses the MERGE insert race retries once and still hides**
- **hiding twice is an upsert, not a PK clash; revealing removes the row**
- **tallyAll answers a whole page in one batch, through the viewer's eyes**

##### Story: PostgreSQL dialect

- **PG16: a concurrent first vote makes MERGE raise the violation Spring maps to DuplicateKeyException — the exact exception the catch+retry is written for**
- **PG16: a vote for a vanished comment is the 23503 FK violation — a DataIntegrityViolation that is NOT a DuplicateKey, and cast() reads it as such**
- **PG16: cast is an upsert — two casts are one row, a direction change stays one row**
- **PG16: cast() catch+retry absorbs the race end to end — first pass loses, retry lands WHEN MATCHED**
- **PG16: hiding twice is an upsert on the real database too**

#### Feature: Kafka configuration pins

##### Story: Consumer offset reset

- **a spring.kafka.consumer.auto-offset-reset dial really reaches the consumer's own configuration**
- **the shipped configuration pins auto-offset-reset to earliest — a group with no committed offset must read history, not skip it**

##### Story: Producer blocking clocks

- **a spring.kafka.producer.properties.* dial really reaches the producer's own configuration**
- **the shipped configuration pins max.block.ms to 5s, and delivery/request to the cascade's clocks**

#### Feature: Meme-deleted cascade

- **a dropped thread is announced on comments-events with every id it took**
- **a meme nobody commented on is announced to nobody — no empty list**
- **a rolled-back cascade announces nothing: the comments are still there**
- **an event that is not a deletion drops no thread and announces nothing**
- **the trace of the deletion continues onto the announcement**

#### Feature: Offline JWT gate

- **a_properly_signed_token_carries_the_caller()**
- **an_under_enrolled_moderator_is_served_as_a_plain_user()**
- **an_unknown_kid_triggers_one_refetch_which_covers_key_rotation()**
- **forged_expired_or_foreign_tokens_are_refused()**

#### Feature: Shared events topic

- **both conversations ride the same topic, keyed by what each is about**
- **the cascade's new type shares the topic without disturbing the saga's traffic**

#### Feature: Transaction boundaries

- **DeleteComment: a crash on the final delete rolls the vote purge back too**
- **DeleteThread: a committed cascade drops votes and thread, then names what it dropped**
- **DeleteThread: a crash on the thread delete rolls the vote purges back — and announces nothing**
- **PurgeUserComments: a crash on the final voter purge rolls the anonymisation back**

#### Feature: Transactional outbox

- **THE PROMOTION: a committed hop whose send never got through is delivered later, unchanged**
- **a fresh unpublished row is left alone — its first attempt may still be in flight**
- **a retention of zero or less refuses the boot, naming the property and echoing the value**
- **a rolled-back hop leaves NO row and sends NOTHING — the round-9 guarantee, now the table's**
- **a thread of thousands is stored and redelivered intact — the payload column is TEXT**
- **retention reaps delivered rows past the threshold and never an undelivered one**
- **the envelope id IS the row id — which is what makes a redelivery a recognizable duplicate**
- **the happy path publishes exactly once: the republisher does not double a marked row**

### Epic: Saga

#### Feature: Marked comment invisibility

##### Story: Public reads

- **a marked comment leaves the thread; everybody else's stays**
- **the compensation gives the comment back with its text, author and score**

##### Story: Static SQL guard

- **no query outside the erasure adapter reads the comments table directly**
- **the exemption is earned: the erasure adapter really does read the table**

#### Feature: Purge command handling

- **a PESEL in a broken rule never reaches the log**
- **a command type this participant does not know is ignored, not guessed at**
- **a completed mark confirms the SAME saga it was commanded for — and erases nothing**
- **a malformed payload is dropped WITHOUT echoing it — it may carry an e-mail**
- **a mark that fails confirms nothing and lets the failure out — so Kafka redelivers**
- **a phone number in a broken rule never reaches the log — not even in digit chunks**
- **a purge command with a blank email is dropped the same way**
- **a purge command without an email is dropped: no purge, no confirmation**
- **a successful mark logs the saga id, never the leaver's e-mail**
- **an UPPERCASE e-mail in a broken rule never reaches the log**
- **an unparseable purge rule is dropped WITHOUT echoing its raw text**
- **the closure erases, and is NOT confirmed — the orchestrator has already decided**
- **the compensation restores, erases nothing and is not confirmed either**

#### Feature: Purge confirmation

##### Story: Outbox delivery

- **a purge that throws writes nothing at all and lets the failure out to the container**
- **a rolled-back purge leaves NO confirmation — the row shares the erasure's fate**
- **a send the broker never confirms leaves the confirmation OWED — and the republisher pays it**
- **the confirmation goes out on comments-events, keyed by the saga, carrying the trace**

##### Story: Topic name

- **and it is keyed by the saga run, which the outbox's 64-char key column can hold**
- **the confirmation is published on the topic the orchestrator listens to for this participant**

#### Feature: Purge retries

- **a store outage that does not pass: the retrying ends with the budget, not never**
- **a store outage that passes: the command is redelivered and the mark then happens**
- **the drop is counted and logged by coordinates — never by payload, which names the leaver**

##### Story: Retry budget

- **a spent budget stops instead of retrying forever — that is the whole point**
- **blocking failures: fewer attempts, spread over minutes, because their time counts too**
- **every record gets its own deadline, not a share of a global one**
- **fast failures: the pauses double to the 15s cap and the whole budget is 90s of them**
- **the last pause is trimmed to the budget instead of overshooting it**
- **the service runs on the documented numbers, not on a test's**

### Epic: Use case

#### Feature: Hide comment

##### Story: Race with deletion

- **a comment deleted mid-hide reads as 'no such comment', not as an error**

#### Feature: Idempotent commands

- **DECLARED EXCEPTION: adding a comment twice is two comments — by design**
- **DECLARED EXCEPTION: voting twice retracts the vote — a toggle, by design**
- **compensate: mark, then restore**
- **delete a comment (author's own)**
- **delete a comment that is not there**
- **delete a whole thread**
- **hide a comment (moderator)**
- **mark a leaver's comments for erasure**
- **purge a leaver's comments (default rule)**

#### Feature: List comments

##### Story: Vote store degradation

- **when the vote store throws, the thread lists with null tallies instead of failing**
- **with a healthy store the batch read feeds every comment's tally**

#### Feature: Purge and thread cascade

- **a closure that arrives without a mark erases nothing**
- **a deleted meme's whole thread goes, votes included**
- **a meme nobody commented on reports nothing to pass on**
- **default purge keeps texts as 'deleted account'; KEEP_POPULAR decides by score**
- **the compensation puts the conversation back exactly as it was**
- **the mark hides the leaver's comments and destroys nothing**

#### Feature: Vote on comment

##### Story: Race with deletion

- **a comment deleted mid-vote reads as 'no such comment', not as an error**

