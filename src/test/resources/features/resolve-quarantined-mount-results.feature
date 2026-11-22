Feature: Resolving a mount result in quarantine writes it again to its data mart

  A result the mounter could not write stays in quarantine until a person resolves it. Republishing or amending it
  writes it to its data mart with the partitioning that data mart has now. If it still cannot be written, it stays
  pending and the person is told why.

  Background:
    Given the data mart "gold/payments" is partitioned by "country"
    And this result of "gold/payments" is pending in quarantine because the attribute "country" is missing:
      | day        | amount |
      | 2026-11-21 | 7      |

  @DEV-97
  Scenario: Republishing a result after its data mart changed its partitioning writes it
    Given the data mart "gold/payments" is now partitioned by "day"
    When "ana" republishes the result
    Then "gold/payments" has the result in the partition "day=2026-11-21"
    And the quarantine of the result is republished by "ana"

  @DEV-97
  Scenario: Republishing a result that still lacks its partition attribute leaves it pending
    When "ana" republishes the result
    Then "gold/payments" has no results
    And the quarantine of the result is still pending
    And "ana" is told that the attribute "country" is missing

  @DEV-97
  Scenario: Amending a result with its partition attribute writes the amended result
    When "ana" amends the result with:
      | day        | amount | country |
      | 2026-11-21 | 7      | FR      |
    Then "gold/payments" has the amended result in the partition "country=FR"
    And the quarantine of the result is corrected by "ana"
    And the quarantine of the result points to the amended result

  @DEV-97
  Scenario: A result cannot be let through without validation
    When "ana" lets the result through because "the country is not needed"
    Then "gold/payments" has no results
    And the quarantine of the result is still pending
    And "ana" is told that a mount result cannot be let through

  @DEV-97
  Scenario: Purging a result discards it
    When "ana" purges the result
    Then "gold/payments" has no results
    And the quarantine of the result is purged by "ana"

  @DEV-97
  Scenario: A janitor that cannot write to the data mart of a result leaves it pending
    Given the janitor cannot write to "gold/payments"
    When "ana" republishes the result
    Then the quarantine of the result is still pending
