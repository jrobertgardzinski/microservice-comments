Feature: Adding a COMMENT

  Signed-in USERS discuss under an existing MEME. The COMMENT's author is the identity
  the security service confirmed, never a field of the request — reading the portal is
  public, so writing is where the door is guarded. And a COMMENT is a remark, not an
  essay: the THREAD stays a conversation, not a blog.

  Rule: A signed-in USER comments under a MEME that exists

    Example:
      Given a signed-in USER
      When the USER comments "Świetny mem!" under the known MEME
      Then the THREAD of the known MEME shows 1 COMMENT by the USER

  Rule: Without signing in there is no commenting

    Example:
      When someone comments anonymously under the known MEME
      Then the COMMENT is refused as sign-in required

  Rule: A COMMENT needs a real MEME to hang under

    Example:
      Given a signed-in USER
      When the USER comments "Halo?" under a MEME nobody has seen
      Then the COMMENT is refused because the MEME is unknown

  Rule: A COMMENT is a remark, not an essay

    Example:
      Given a signed-in USER
      When the USER posts a COMMENT of 2001 characters under the known MEME
      Then the COMMENT is refused as too long
      When the USER posts a COMMENT of 2000 characters under the known MEME
      Then the COMMENT is accepted
