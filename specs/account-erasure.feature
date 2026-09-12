# The purge arrives over the broker and not over HTTP, so these scenarios drive the use cases
# directly instead of the API — the wire is PurgeCommandsContractTest's business, not this file's.
# What is described here is the PROMISE this service makes to the three others it shares a leaver
# with; how the promise is carried (a saga, an orchestrator, confirmations) is deliberately absent.
Feature: What a leaver's words are owed

  Anything this service holds of a person who is leaving has to go. Until somebody says
  the decision is final, it has to be possible to give it all back — so the COMMENTS are
  first taken out of sight and only later destroyed, and between those two moments nobody
  reading the portal can tell the difference from gone.

  Being out of sight is not a courtesy here. Every other service holding that person's
  things is deciding at the same time, and any one of them may fail; if this one had
  already shredded the words, there would be nothing to undo and the person would get
  their account back without their comments.

  What "destroyed" means is not this service's call either. A COMMENT is somebody else's
  conversation as much as its author's, so by default the words stay and the name goes —
  the thread reads on, signed "deleted account". Only an ADMIN closing somebody else's
  account may ask for something different.

  Background:
    Given the USER's COMMENT "I am leaving" under the known MEME

  Rule: Taken out of sight, the words are already gone as far as anyone can tell

    Example:
      When the USER's things are set aside
      Then the THREAD of the known MEME is empty

  Rule: Nothing is destroyed until the decision is final

    # this is the half that makes the promise worth making: a sibling service failing
    # must be able to undo everything, and it cannot undo a delete
    Example:
      Given the USER's things are set aside
      When the decision is taken back
      Then the THREAD of the known MEME shows 1 COMMENT by the USER

  Rule: Once it is final, the conversation survives and the author does not

    Example:
      Given the USER's things are set aside
      When the decision is made final
      Then the THREAD of the known MEME shows 1 COMMENT signed "deleted account"

  Rule: An ADMIN may ask for the words to go with the name

    Example:
      Given the USER's things are set aside
      When the decision is made final, erasing the words themselves
      Then the THREAD of the known MEME is empty

  Rule: Making it final destroys only what was set aside

    # somebody who comments after the mark is not part of that decision — the closure
    # acts on what it reserved, never on "everything by that author"
    Example:
      Given the USER's things are set aside
      And the USER's COMMENT "written afterwards" under the known MEME
      When the decision is made final, erasing the words themselves
      Then the THREAD of the known MEME shows 1 COMMENT by the USER
