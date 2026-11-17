Feature: The janitor keeps the events the inbox could not apply

  When the inbox fails while the janitor applies an event, the janitor tries that event again in its next
  round instead of losing it, and gives up after the configured number of attempts.

  @DEV-43
  Scenario: An envelope is quarantined although the inbox failed once
    Given the envelope "order-1" is captured in the inbox
    And the inbox fails the next time envelopes are quarantined
    When the janitor learns that "order-1" must be quarantined
    And the janitor runs 2 rounds
    Then "order-1" is quarantined in the inbox
    And "order-1" has a single quarantine entry

  @DEV-43
  Scenario: A persisted envelope is marked although the inbox failed once
    Given the envelope "order-2" is captured in the inbox
    And the inbox fails the next time envelopes are marked as persisted
    When the janitor learns that "order-2" was persisted
    And the janitor runs 2 rounds
    Then "order-2" is persisted in the inbox

  @DEV-43
  Scenario: A failure marking envelopes as persisted does not hold back a quarantine
    Given these envelopes are captured in the inbox:
      | envelope |
      | order-3  |
      | order-4  |
    And the inbox fails the next time envelopes are marked as persisted
    When the janitor learns that "order-3" was persisted
    And the janitor learns that "order-4" must be quarantined
    And the janitor runs 1 round
    Then "order-4" is quarantined in the inbox
    And "order-3" is still captured in the inbox

  @DEV-43
  Scenario: The janitor gives up on an event after the configured attempts
    Given the janitor tries each event 2 times
    And the envelope "order-5" is captured in the inbox
    And the inbox always fails when envelopes are marked as persisted
    When the janitor learns that "order-5" was persisted
    And the janitor runs 3 rounds
    Then the janitor reports that it gave up on "order-5"
    And "order-5" is still captured in the inbox
