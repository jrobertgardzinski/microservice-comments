Feature: Voting on a COMMENT

  A signed-in USER has ONE VOTE per COMMENT, worked as a toggle: repeating the same
  VOTE takes it back. The THREAD carries each COMMENT's SCORE, so a reader sees at
  a glance what the room thinks.

  Rule: One USER, one VOTE — and repeating the VOTE retracts it

    Example:
      Given a USER
      And the USER's COMMENT "Plusujcie" under the known MEME
      When 2 USERS up-vote that COMMENT
      And the same second USER up-votes it again
      Then the THREAD shows that COMMENT with a SCORE of 1
