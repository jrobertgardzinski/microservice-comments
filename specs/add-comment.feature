# engineer's note: the author is taken from the confirmed identity behind the token,
# never from the request body — the sign-in gate is where that promise is kept
Feature: Adding a COMMENT

  USERS discuss under an existing MEME: a GUEST reads, writing takes a USER, and
  every COMMENT is signed by who really wrote it — nobody puts words in someone
  else's mouth. A COMMENT is a remark, not an essay: the THREAD stays a
  conversation, not a blog.

  Rule: A USER comments under a MEME that exists

    Example:
      Given a USER
      When the USER comments "Świetny mem!" under the known MEME
      Then the THREAD of the known MEME shows 1 COMMENT by the USER

  Rule: A GUEST may read, not write

    Example:
      When a GUEST comments under the known MEME
      Then the COMMENT is refused as sign-in required

  Rule: A COMMENT needs a real MEME to hang under

    Example:
      Given a USER
      When the USER comments "Halo?" under a MEME nobody has seen
      Then the COMMENT is refused because the MEME is unknown

  Rule: A COMMENT is a remark, not an essay

    Example:
      Given a USER
      When the USER posts a COMMENT of 2001 characters under the known MEME
      Then the COMMENT is refused as too long
      When the USER posts a COMMENT of 2000 characters under the known MEME
      Then the COMMENT is accepted
