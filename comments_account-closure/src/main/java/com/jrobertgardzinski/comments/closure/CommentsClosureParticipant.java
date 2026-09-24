package com.jrobertgardzinski.comments.closure;

import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.comments.domain.Observation;
import com.jrobertgardzinski.observation.Observations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The comments service's side of the account-closure saga: a participant in TWO phases, which is
 * what makes the whole saga compensatable.
 *
 * <ul>
 *   <li>{@code PURGE_USER_CONTENT} — the reversible step: the leaver's comments are MARKED
 *       ({@link MarkUserCommentsForErasure}), which takes them out of every thread and deletes
 *       nothing, and a confirmation goes back. The name on the wire is unchanged on purpose — the
 *       orchestrator's contract is "make this leaver's content go away and tell me when".</li>
 *   <li>{@code ERASE_USER_CONTENT} — the closure: everybody confirmed, the case cannot fail any
 *       more, and {@link PurgeUserComments} applies the rule for real.</li>
 *   <li>{@code RESTORE_USER_CONTENT} — the compensation: a sibling participant failed, so the
 *       marks come off ({@link RestoreUserComments}) and the conversation is whole again.</li>
 * </ul>
 *
 * <p>All three are idempotent, so at-least-once delivery needs no extra dedup. Only the first is
 * confirmed: the other two are the orchestrator ENDING the case, and answering them would tell it
 * something it has already decided. What guards them is retrying and — for a closure lost beyond
 * the retry budget — the backlog of marks nobody closed being visible and alarmed on rather than
 * swept on a timer. Both of those belong to whatever carries the commands, and live with it.
 *
 * <p><strong>Why this is a module of its own and not a class in the adapter.</strong> Everything
 * above is true of the comments axis whether the commands arrive over Kafka or as a method call
 * in a single process. Nothing in here knows which it is, so both assemblies run the SAME
 * decisions — and the flow can be read, and tested, before anyone picks one. Its twin is
 * memes_account-closure, and the two being separately readable is how the difference between
 * their axes stays visible.
 */
public final class CommentsClosureParticipant {

    private static final Logger LOG = LoggerFactory.getLogger(CommentsClosureParticipant.class);

    /** The reversible mark; its confirmation is what the orchestrator's quorum counts. */
    public static final String MARK = ClosureMessages.PURGE_USER_CONTENT;
    /** The closure: the orchestrator says the case is settled, so the rule may be applied. */
    public static final String ERASE = ClosureMessages.ERASE_USER_CONTENT;
    /** The compensation: the marks come off and the comments are back in their threads. */
    public static final String RESTORE = ClosureMessages.RESTORE_USER_CONTENT;

    private final MarkUserCommentsForErasure markForErasure;
    private final RestoreUserComments restoreUserComments;
    private final PurgeUserComments purgeUserComments;
    private final ClosureConfirmations confirmations;
    private final Observations<Observation> observations;
    private final Atomically atomically;

    public CommentsClosureParticipant(MarkUserCommentsForErasure markForErasure,
                                      RestoreUserComments restoreUserComments,
                                      PurgeUserComments purgeUserComments,
                                      ClosureConfirmations confirmations,
                                      Observations<Observation> observations,
                                      Atomically atomically) {
        this.markForErasure = markForErasure;
        this.restoreUserComments = restoreUserComments;
        this.purgeUserComments = purgeUserComments;
        this.confirmations = confirmations;
        this.observations = observations;
        this.atomically = atomically;
    }

    /** What this service does about one command of a closing account. */
    public ClosureOutcome handle(ClosureCommand command) {
        String type = command.type();
        if (!MARK.equals(type) && !ERASE.equals(type) && !RESTORE.equals(type)) {
            return new ClosureOutcome.NotOurs(type);
        }
        String sagaId = command.sagaId();
        if (!command.isAddressed()) {
            // no email, nothing to act on and no key to confirm under — dropped WITHOUT
            // confirming, so the orchestrator's timeout (not a hollow success) surfaces the
            // broken command
            LOG.warn("dropping {} without an email (saga {})", type, sagaId);
            return new ClosureOutcome.Unaddressed(type);
        }
        String email = command.email();
        return switch (type) {
            case MARK -> {
                int reserved = markAndConfirm(sagaId, email);
                // the saga id identifies the run in logs; the e-mail is PII and stays out of INFO.
                // The count is not PII and is the difference between "it worked" and "it found
                // nobody"
                LOG.info("marked {} of one leaver's comments for erasure (saga {})", reserved, sagaId);
                yield new ClosureOutcome.Reserved(reserved);
            }
            case ERASE -> {
                // the rule is resolved BEFORE the step opens: it is pure reading, it can say so
                // loudly about an unreadable rule, and none of that belongs inside the unit of
                // work that destroys things. It rides the CLOSURE, because that is where the rule
                // is finally applied
                Optional<PurgeRule> rule = requestedRule(command);
                atomically.run(() -> purgeUserComments.execute(email, rule));
                LOG.info("erased one leaver's marked comments on the saga's closure (saga {})", sagaId);
                yield new ClosureOutcome.Erased();
            }
            case RESTORE -> {
                atomically.run(() -> restoreUserComments.execute(email));
                LOG.info("restored one leaver's comments: the saga compensated (saga {})", sagaId);
                yield new ClosureOutcome.Restored();
            }
            default -> throw new IllegalStateException("unreachable: " + type);
        };
    }

    /**
     * The mark and the promise to report it, as ONE step. A failure anywhere inside propagates to
     * the caller, which is what lets the carrier retry the command — the mark being idempotent,
     * running the whole thing again is safe.
     *
     * <p>The confirmation is made INSIDE, not after: confirming after the step committed would
     * leave a window where the comments are hidden and nothing owes the orchestrator a word about
     * it, which is the failure mode that ends with the leaver holding a restored account whose
     * content nobody can see.
     *
     * <p><strong>And it says how much it reserved.</strong> The confirmation used to go out
     * unconditionally, so a mark that matched nothing was reported in exactly the same words as
     * one that took forty comments out of their threads — which is how a leaver who had changed
     * their address got a completed deletion with every word still public. A zero raises
     * {@link Observation.PurgeReservedNothing} and a WARN, because this is the one thing the
     * service cannot resolve on its own: "I hold nothing of theirs" and "their rows are under the
     * address they had yesterday and the rename has not reached me yet" are the same observation
     * from in here, and the address on the command is all there is to go on.
     */
    private int markAndConfirm(String sagaId, String email) {
        AtomicInteger reserved = new AtomicInteger();
        atomically.run(() -> {
            int marked = markForErasure.execute(email);
            confirmations.confirm(sagaId, email, marked);
            reserved.set(marked);
        });
        if (reserved.get() == 0) {
            observations.record(new Observation.PurgeReservedNothing());
            LOG.warn("confirmed a purge that reserved NOTHING (saga {}): either this member never"
                    + " commented, or their comments are still keyed by an address they have"
                    + " changed and the rename has not been consumed yet", sagaId);
        }
        return reserved.get();
    }

    /**
     * The rule for THIS service's axis, and the one gate that is not a matter of configuration.
     *
     * <p>A closure the OWNER asked for resolves to {@link PurgeRule.Delete}, STATED rather than
     * left absent: absent means "decide for me", which lets the deployment default answer, and a
     * deployment dialled in to keep anonymised comments would then keep the words of somebody who
     * asked to be forgotten. The right to erasure has no exception for content worth keeping.
     *
     * <p>An administrator's closure is an ordinary business decision, so its rule is read from the
     * command as it always was.
     */
    private Optional<PurgeRule> requestedRule(ClosureCommand command) {
        // the command answers it, not a constant of ours: the one word that licenses conditions
        // belongs to the agreement, and its reading is deliberately not symmetrical — a missing
        // field, an empty one or a word nobody recognises is a closure the OWNER asked for
        if (!command.allowsConditions()) {
            if (command.rule().isPresent()) {
                // a producer that states conditions on a self-closure is broken, not permissive
                LOG.warn("a self-requested closure arrived carrying a comments purge rule; ignoring "
                        + "it and deleting — conditions are an administrator's to state");
            }
            return Optional.of(new PurgeRule.Delete());
        }
        if (command.rule().isEmpty()) {
            return Optional.empty();
        }
        String text = command.rule().get();
        try {
            return Optional.of(PurgeRule.parse(text));
        } catch (IllegalArgumentException invalid) {
            // invalid.getMessage() is safe to log now, and that is the whole point of having moved
            // the vocabulary into the library: the refusal states the length and the SHAPE, never
            // the text, so a new caller cannot reintroduce the leak by logging the obvious thing
            LOG.warn("ignoring an unparseable comments purge rule, using the default: {}",
                    invalid.getMessage());
            return Optional.empty();
        }
    }
}
