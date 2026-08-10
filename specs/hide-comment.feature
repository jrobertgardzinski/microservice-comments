Feature: Hiding a COMMENT

  Moderation that can change its mind: instead of deleting, a MODERATOR hides a
  COMMENT. A GUEST sees a tombstone without the words, the author still sees
  their own — marked hidden — and revealing brings the words back. Hiding is a
  MODERATOR's call, and it must be a decision, not a shrug.

  Background:
    Given a USER

  Rule: Hidden for a GUEST, never for the author — and reversible

    Example:
      Given the USER's COMMENT "Kontrowersyjne" under the known MEME
      When a MODERATOR hides that COMMENT
      Then a GUEST sees that COMMENT as hidden without its text
      And the author still sees that COMMENT's text, marked hidden
      When a MODERATOR reveals that COMMENT
      Then a GUEST sees that COMMENT's text again

  Rule: Hiding is a MODERATOR's call

    Example:
      Given the USER's COMMENT "Nie zdejmiesz" under the known MEME
      When another USER tries to hide that COMMENT
      Then the hiding is refused as not-a-MODERATOR

  Rule: Hiding needs an unambiguous decision

    Example:
      Given the USER's COMMENT "Wciąż mnie widać" under the known MEME
      When a MODERATOR asks about that COMMENT without deciding hidden or not
      Then the request is refused as undecided
      And a GUEST still sees that COMMENT's text
