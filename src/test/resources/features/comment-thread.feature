Feature: Comment threads under memes

  Signed-in users discuss under an existing meme; the COMMENT's author comes from the security
  service, not from the request body. Votes on a COMMENT are one-per-user toggles, and the listing
  carries each COMMENT's score. When a meme is deleted, its whole thread of COMMENTs disappears
  with it.

  Nouns:
    COMMENT* -> Comment

  Scenario: a signed-in user comments and the thread lists it
    Given a signed-in user
    When she comments "Świetny mem!" under the known meme
    Then the thread of the known meme shows 1 comment by her

  Scenario: an anonymous comment is refused
    When someone comments anonymously under the known meme
    Then the comment is refused as sign-in required

  Scenario: a comment under a meme that does not exist is refused
    Given a signed-in user
    When she comments "Halo?" under a meme nobody has seen
    Then the comment is refused because the meme is unknown

  Scenario: comment votes are one-per-user and the listing carries the score
    Given a signed-in user
    And her comment "Plusujcie" under the known meme
    When 2 users up-vote that comment
    And the same second user up-votes it again
    Then the thread shows that comment with a score of 1

  Scenario: a long thread is read one page at a time
    Given a signed-in user
    And 5 comments under the known meme
    When she reads page 0 of size 2 of the thread
    Then 2 comments are returned
    When she reads page 2 of size 2 of the thread
    Then 1 comment is returned

  Scenario: an essay is refused; a remark is accepted
    Given a signed-in user
    When she posts a comment of 2001 characters under the known meme
    Then the comment is refused as too long
    When she posts a comment of 2000 characters under the known meme
    Then the comment is accepted

  Scenario: a deleted meme takes its whole thread with it
    Given a signed-in user
    And her comment "Znikne razem z memem" under the known meme
    When the meme service announces the meme was deleted
    Then the thread of the known meme is empty
