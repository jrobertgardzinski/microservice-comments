Feature: Comment threads under memes

  Signed-in users discuss under an existing meme; identity comes from the security service, not
  from the request body. Comment votes are one-per-user toggles, and the listing carries each
  comment's score. When a meme is deleted, its whole thread disappears with it.

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

  Scenario: a deleted meme takes its whole thread with it
    Given a signed-in user
    And her comment "Znikne razem z memem" under the known meme
    When the meme service announces the meme was deleted
    Then the thread of the known meme is empty
