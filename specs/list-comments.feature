Feature: Reading a THREAD

  Reading is public: anyone browses a MEME's THREAD one page at a time. The listing
  guards privacy — a COMMENT is signed with a masked name and the full address never
  leaves the service — while the author still recognises their own words. VOTES are
  a side dish: when the tally is unavailable, the THREAD still reads.

  Background:
    Given a signed-in USER

  Rule: A long THREAD is read one page at a time — every COMMENT exactly once

    Example:
      Given 5 COMMENTS under the known MEME
      When the USER reads page 0 of size 2 of the THREAD
      Then 2 COMMENTS are returned
      When the USER reads page 2 of size 2 of the THREAD
      Then 1 COMMENT is returned
      # Counting alone proved only that five rows exist. It could not tell a correct paging from one
      # that shows the same comment on two pages and never shows another — which is a live risk here,
      # because the query orders by created_at with no tie-break and comments written in a loop share
      # a millisecond. Reading every page and comparing the IDS is what makes this a guard.
      When the USER reads every page of size 2 of the THREAD
      Then the pages together show each of the 5 COMMENTS exactly once

  Rule: The listing signs a COMMENT with a masked name

    Example:
      Given the USER's COMMENT "Miło poznać" under the known MEME
      Then a reader learns who wrote it only as a masked name
      And the USER's full address appears nowhere in the listing

  Rule: Behind the mask, the author still recognises their own words

    Example:
      Given the USER's COMMENT "To moje słowa" under the known MEME
      Then the author still recognises that COMMENT as their own
      But another signed-in USER sees it as someone else's

  Rule: The THREAD survives the VOTE count going missing

    Example:
      Given the USER's COMMENT "Głosy to dodatek" under the known MEME
      And 2 USERS up-vote that COMMENT
      When the VOTE count becomes unavailable
      Then the THREAD still shows that COMMENT, its result unknown
      When the VOTE count is back
      Then the THREAD shows that COMMENT with a SCORE of 2
