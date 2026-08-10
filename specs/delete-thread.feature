Feature: A deleted MEME takes its THREAD along

  When the meme service announces that a MEME was deleted, the whole THREAD of
  COMMENTS disappears with it. This service is the only one that knew which
  COMMENTS hung there — so it passes that list on to whoever saved them, and
  stays silent when there was nothing to take.

  Background:
    Given a signed-in USER

  Rule: The THREAD goes with the MEME, and the cascade passes the baton on

    Example:
      Given the USER's COMMENT "Znikne razem z memem" under the known MEME
      When the meme service announces the MEME was deleted
      Then the THREAD of the known MEME is empty
      And the collections service is told which COMMENTS went

  Rule: A MEME nobody commented on is announced to nobody

    Example:
      When the meme service announces the MEME was deleted
      Then the THREAD of the known MEME is empty
      And nothing is announced to the collections service
