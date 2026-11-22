Feature: The janitor marks each envelope as persisted once while it remembers its key

  The janitor marks an envelope as PERSISTED when it learns that the ingester wrote it, and the TTL of the
  envelope starts then. A repeated EnvelopePersisted would start the TTL again. The janitor remembers during a
  window the keys it marked, and a key is remembered only once the inbox applied it (ADR-007).

  Background:
    Given a janitor that remembers the keys it marked for 10 minutes

  @DEV-64
  Scenario: An envelope announced as persisted again is not marked again
    Given the envelope "order-1" is captured in the inbox
    When the janitor learns that "order-1" was persisted
    And the janitor runs 1 round
    And the janitor learns that "order-1" was persisted
    And the janitor runs 1 round
    Then the inbox marked "order-1" as persisted once

  @DEV-64
  Scenario: An envelope the inbox could not mark is not remembered
    Given the envelope "order-2" is captured in the inbox
    And the janitor tries each event 1 time
    And the inbox fails the next time envelopes are marked as persisted
    When the janitor learns that "order-2" was persisted
    And the janitor runs 1 round
    And the janitor learns that "order-2" was persisted
    And the janitor runs 1 round
    Then "order-2" is persisted in the inbox

  @DEV-64
  Scenario: After the window, the same envelope is marked again
    Given the envelope "order-3" is captured in the inbox
    When the janitor learns that "order-3" was persisted
    And the janitor runs 1 round
    And 11 minutes pass
    And the janitor learns that "order-3" was persisted
    And the janitor runs 1 round
    Then the inbox marked "order-3" as persisted 2 times
