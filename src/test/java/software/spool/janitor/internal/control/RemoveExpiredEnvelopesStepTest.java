package software.spool.janitor.internal.control;

import org.junit.jupiter.api.Test;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.vo.*;
import software.spool.core.adapter.memory.InMemoryProcessedKeyRepository;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.port.idempotency.ProcessedKeyRepository;
import software.spool.core.port.idempotency.ProcessedKeyWindow;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.metrics.SpoolMetrics;
import software.spool.core.utils.routing.ErrorRouter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RemoveExpiredEnvelopesStepTest {

    @Test
    void apply_expiredEnvelopes_removedFromInbox() {
        List<IdempotencyKey> removed = new ArrayList<>();
        Envelope expired = anyEnvelope(Instant.parse("2000-01-01T00:00:00Z"));
        RemoveExpiredEnvelopesStep step = new RemoveExpiredEnvelopesStep(
            new ErrorRouter(),
            Duration.ofDays(365),
            keys -> { removed.addAll(keys); return List.of(); },
            status -> List.of(expired),
            (value, attributes) -> {}
        );

        step.apply(PipelineContext.empty());

        assertThat(removed).containsExactly(expired.idempotencyKey());
    }

    @Test
    void apply_freshEnvelopes_notRemoved() {
        List<IdempotencyKey> removed = new ArrayList<>();
        Envelope fresh = anyEnvelope(Instant.now());
        RemoveExpiredEnvelopesStep step = new RemoveExpiredEnvelopesStep(
            new ErrorRouter(),
            Duration.ofDays(365),
            keys -> { removed.addAll(keys); return List.of(); },
            status -> List.of(fresh),
            (value, attributes) -> {}
        );

        step.apply(PipelineContext.empty());

        assertThat(removed).isEmpty();
    }

    @Test
    void apply_asksTheInboxOnlyForWhatIsOldEnough() {
        List<EnvelopeStatus> askedStatuses = new ArrayList<>();
        List<Instant> askedLimits = new ArrayList<>();
        List<IdempotencyKey> removed = new ArrayList<>();
        Envelope expired = anyEnvelope(Instant.parse("2000-01-01T00:00:00Z"));
        Instant before = Instant.now();
        RemoveExpiredEnvelopesStep step = new RemoveExpiredEnvelopesStep(
            new ErrorRouter(),
            Duration.ofDays(1),
            keys -> { removed.addAll(keys); return List.of(); },
            inboxAnswering(askedStatuses, askedLimits, expired),
            (value, attributes) -> {}
        );

        step.apply(PipelineContext.empty());
        Instant after = Instant.now();

        assertThat(askedStatuses).containsExactly(EnvelopeStatus.PERSISTED);
        assertThat(askedLimits).singleElement().satisfies(limit ->
            assertThat(limit).isBetween(before.minus(Duration.ofDays(1)), after.minus(Duration.ofDays(1))));
        assertThat(removed).containsExactly(expired.idempotencyKey());
    }

    @Test
    void apply_whatTheInboxReturns_isRemovedWithoutFilteringItAgain() {
        List<IdempotencyKey> removed = new ArrayList<>();
        Envelope answeredByTheInbox = anyEnvelope(Instant.now());
        RemoveExpiredEnvelopesStep step = new RemoveExpiredEnvelopesStep(
            new ErrorRouter(),
            Duration.ofDays(365),
            keys -> { removed.addAll(keys); return List.of(); },
            inboxAnswering(new ArrayList<>(), new ArrayList<>(), answeredByTheInbox),
            (value, attributes) -> {}
        );

        step.apply(PipelineContext.empty());

        assertThat(removed).containsExactly(answeredByTheInbox.idempotencyKey());
    }

    @Test
    void apply_forQuarantinedEnvelopes_asksForThatStatusAndCountsWithItsOwnReason() {
        List<EnvelopeStatus> askedStatuses = new ArrayList<>();
        Map<String, Long> countedByReason = new HashMap<>();
        Envelope quarantined = anyEnvelope(Instant.parse("2000-01-01T00:00:00Z"));
        RemoveExpiredEnvelopesStep step = new RemoveExpiredEnvelopesStep(
            new ErrorRouter(),
            Duration.ofDays(1),
            keys -> List.of(),
            inboxAnswering(askedStatuses, new ArrayList<>(), quarantined),
            (value, attributes) -> countedByReason.merge(attributes.get(SpoolMetrics.Attributes.REASON), value, Long::sum),
            EnvelopeStatus.QUARANTINED,
            "quarantine_expired"
        );

        step.apply(PipelineContext.empty());

        assertThat(askedStatuses).containsExactly(EnvelopeStatus.QUARANTINED);
        assertThat(countedByReason).containsOnly(Map.entry("quarantine_expired", 1L));
    }

    @Test
    void apply_persistedEnvelopes_stillCountWithTheReasonExpired() {
        Map<String, Long> countedByReason = new HashMap<>();
        RemoveExpiredEnvelopesStep step = new RemoveExpiredEnvelopesStep(
            new ErrorRouter(),
            Duration.ofDays(1),
            keys -> List.of(),
            inboxAnswering(new ArrayList<>(), new ArrayList<>(), anyEnvelope(Instant.parse("2000-01-01T00:00:00Z"))),
            (value, attributes) -> countedByReason.merge(attributes.get(SpoolMetrics.Attributes.REASON), value, Long::sum)
        );

        step.apply(PipelineContext.empty());

        assertThat(countedByReason).containsOnly(Map.entry("expired", 1L));
    }

    @Test
    void apply_nothingOldEnough_countsNothing() {
        Map<String, Long> countedByReason = new HashMap<>();
        RemoveExpiredEnvelopesStep step = new RemoveExpiredEnvelopesStep(
            new ErrorRouter(),
            Duration.ofDays(1),
            keys -> List.of(),
            status -> List.of(),
            (value, attributes) -> countedByReason.merge(attributes.get(SpoolMetrics.Attributes.REASON), value, Long::sum),
            EnvelopeStatus.QUARANTINED,
            "quarantine_expired"
        );

        step.apply(PipelineContext.empty());

        assertThat(countedByReason).isEmpty();
    }

    @Test
    void apply_withADeduplicationWindow_remembersTheKeyOfEachRemovedEnvelopeFromWhenItWasProcessed() {
        List<String> happened = new ArrayList<>();
        Instant processedAt = Instant.parse("2000-01-01T00:00:00Z");
        Envelope expired = anyEnvelope(processedAt);
        ProcessedKeyRepository processedKeys = recording(happened);
        RemoveExpiredEnvelopesStep step = new RemoveExpiredEnvelopesStep(
            new ErrorRouter(),
            Duration.ofDays(1),
            keys -> { keys.forEach(key -> happened.add("removed " + key.value())); return List.of(); },
            status -> List.of(expired),
            (value, attributes) -> {},
            Optional.of(new ProcessedKeyWindow(processedKeys, Duration.ofDays(365 * 100), Clock.systemUTC()))
        );

        step.apply(PipelineContext.empty());

        assertThat(happened).containsExactly("remembered " + expired.idempotencyKey().value() + " at " + processedAt,
                "removed " + expired.idempotencyKey().value());
        assertThat(processedKeys.contains(expired.idempotencyKey())).isTrue();
    }

    @Test
    void apply_withADeduplicationWindow_forgetsTheKeysProcessedLongerThanTheWindowAgo() {
        InMemoryProcessedKeyRepository processedKeys = new InMemoryProcessedKeyRepository();
        IdempotencyKey old = IdempotencyKey.of("old");
        IdempotencyKey recent = IdempotencyKey.of("recent");
        processedKeys.save(new ProcessedKey(old, Instant.now().minus(Duration.ofDays(31))));
        processedKeys.save(new ProcessedKey(recent, Instant.now().minus(Duration.ofDays(29))));
        RemoveExpiredEnvelopesStep step = new RemoveExpiredEnvelopesStep(
            new ErrorRouter(),
            Duration.ofDays(1),
            keys -> List.of(),
            status -> List.of(),
            (value, attributes) -> {},
            Optional.of(new ProcessedKeyWindow(processedKeys, Duration.ofDays(30), Clock.systemUTC()))
        );

        step.apply(PipelineContext.empty());

        assertThat(processedKeys.contains(old)).isFalse();
        assertThat(processedKeys.contains(recent)).isTrue();
    }

    private static ProcessedKeyRepository recording(List<String> happened) {
        InMemoryProcessedKeyRepository kept = new InMemoryProcessedKeyRepository();
        return new ProcessedKeyRepository() {
            @Override
            public boolean contains(IdempotencyKey key) {
                return kept.contains(key);
            }

            @Override
            public void save(ProcessedKey processedKey) {
                happened.add("remembered " + processedKey.key().value() + " at " + processedKey.processedAt());
                kept.save(processedKey);
            }

            @Override
            public void forgetProcessedBefore(Instant moment) {
                kept.forgetProcessedBefore(moment);
            }
        };
    }

    private static InboxStatusQuery inboxAnswering(List<EnvelopeStatus> askedStatuses, List<Instant> askedLimits, Envelope answer) {
        return new InboxStatusQuery() {
            @Override
            public Collection<Envelope> findByStatus(EnvelopeStatus status) {
                throw new AssertionError("the whole folder must not be read");
            }

            @Override
            public Collection<Envelope> findByStatusModifiedBefore(EnvelopeStatus status, Instant limit) {
                askedStatuses.add(status);
                askedLimits.add(limit);
                return List.of(answer);
            }
        };
    }

    private static Envelope anyEnvelope(Instant updatedAt) {
        IdempotencyKey key = IdempotencyKey.of("k-" + updatedAt.toEpochMilli());
        return new Envelope(key, new EventMetadata(), MediaType.of("application/json"), "{}".getBytes(), EnvelopeStatus.PERSISTED, 0, updatedAt, updatedAt);
    }
}
