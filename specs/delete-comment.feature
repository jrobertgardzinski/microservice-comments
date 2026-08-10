Feature: Deleting a COMMENT

  A COMMENT belongs to its author: the author may take their own words down, a
  stranger may not, and a MODERATOR may take down anyone's. A single deletion is
  nobody else's business: no cascade is announced for it.

  Background:
    Given a USER

  Rule: The author takes their own COMMENT down; a stranger cannot

    Example:
      Given the USER's COMMENT "Skasuje sam" under the known MEME
      When another USER tries to delete that COMMENT
      Then the deletion is refused as not-theirs
      When the USER deletes that COMMENT
      Then the THREAD of the known MEME is empty
      And nothing is announced to the collections service

  Rule: A MODERATOR takes down anyone's COMMENT

    Example:
      Given the USER's COMMENT "Ktos to zdejmie" under the known MEME
      When a MODERATOR deletes that COMMENT
      Then the THREAD of the known MEME is empty
