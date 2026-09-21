package software.spool.janitor.internal.control;

import org.junit.jupiter.api.Test;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.Event;
import software.spool.core.model.vo.*;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.metrics.SpoolMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepublishStuckEnvelopesStepTest {

    @Test
    void apply_stuckEnvelope_publishesEnvelopeStored() {
        List<Event> published = new ArrayList<>();
        Envelope stuck = anyEnvelope(0, Instant.parse("2000-01-01T00:00:00Z"));
        RepublishStuckEnvelopesStep step = new RepublishStuckEnvelopesStep(
            status -> List.of(stuck),
            (keys, status) -> List.of(),
            new EventPublisher() {
                @Override public <E extends Event> void publish(E event) { published.add(event); }
            },
            Duration.ZERO,
            3,
            (value, attributes) -> {}
        );

        step.apply(PipelineContext.empty());

        assertThat(published).hasSize(1);
    }

    @Test
    void apply_maxRetriesReached_quarantinesInstead() {
        List<Event> published = new ArrayList<>();
        List<IdempotencyKey> quarantined = new ArrayList<>();
        Envelope stuck = anyEnvelope(3, Instant.parse("2000-01-01T00:00:00Z"));
        RepublishStuckEnvelopesStep step = new RepublishStuckEnvelopesStep(
            status -> List.of(stuck),
            (keys, status) -> { if (status == EnvelopeStatus.QUARANTINED) quarantined.addAll(keys); return List.of(); },
            new EventPublisher() {
                @Override public <E extends Event> void publish(E event) { published.add(event); }
            },
            Duration.ZERO,
            3,
            (value, attributes) -> {}
        );

        step.apply(PipelineContext.empty());

        assertThat(published).isEmpty();
        assertThat(quarantined).hasSize(1);
    }

    @Test
    void apply_maxRetriesReached_countsTheQuarantineWithItsOwnReason() {
        Map<String, Long> countedByReason = new HashMap<>();
        Envelope stuck = anyEnvelope(3, Instant.parse("2000-01-01T00:00:00Z"));
        RepublishStuckEnvelopesStep step = new RepublishStuckEnvelopesStep(
            status -> List.of(stuck),
            (keys, status) -> List.of(),
            new EventPublisher() {
                @Override public <E extends Event> void publish(E event) { }
            },
            Duration.ZERO,
            3,
            (value, attributes) -> countedByReason.merge(attributes.get(SpoolMetrics.Attributes.REASON), value, Long::sum)
        );

        step.apply(PipelineContext.empty());

        assertThat(countedByReason).containsOnly(Map.entry("retries_exhausted", 1L));
    }

    @Test
    void apply_stuckEnvelopeStillWithRetriesLeft_countsNothing() {
        Map<String, Long> countedByReason = new HashMap<>();
        Envelope stuck = anyEnvelope(0, Instant.parse("2000-01-01T00:00:00Z"));
        RepublishStuckEnvelopesStep step = new RepublishStuckEnvelopesStep(
            status -> List.of(stuck),
            (keys, status) -> List.of(),
            new EventPublisher() {
                @Override public <E extends Event> void publish(E event) { }
            },
            Duration.ZERO,
            3,
            (value, attributes) -> countedByReason.merge(attributes.get(SpoolMetrics.Attributes.REASON), value, Long::sum)
        );

        step.apply(PipelineContext.empty());

        assertThat(countedByReason).isEmpty();
    }

    @Test
    void apply_severalEnvelopesRunOutOfRetries_countsEachOne() {
        Map<String, Long> countedByReason = new HashMap<>();
        RepublishStuckEnvelopesStep step = new RepublishStuckEnvelopesStep(
            status -> List.of(
                new Envelope(IdempotencyKey.of("a"), new EventMetadata(), MediaType.of("application/json"), "{}".getBytes(), EnvelopeStatus.CAPTURED, 3, Instant.parse("2000-01-01T00:00:00Z"), null),
                new Envelope(IdempotencyKey.of("b"), new EventMetadata(), MediaType.of("application/json"), "{}".getBytes(), EnvelopeStatus.CAPTURED, 5, Instant.parse("2000-01-01T00:00:00Z"), null)),
            (keys, status) -> List.of(),
            new EventPublisher() {
                @Override public <E extends Event> void publish(E event) { }
            },
            Duration.ZERO,
            3,
            (value, attributes) -> countedByReason.merge(attributes.get(SpoolMetrics.Attributes.REASON), value, Long::sum)
        );

        step.apply(PipelineContext.empty());

        assertThat(countedByReason).containsOnly(Map.entry("retries_exhausted", 2L));
    }

    @Test
    void apply_asksTheInboxOnlyForTheCapturedEnvelopesOlderThanTheThreshold() {
        List<EnvelopeStatus> askedStatuses = new ArrayList<>();
        List<Instant> askedLimits = new ArrayList<>();
        List<Event> published = new ArrayList<>();
        Envelope stuck = anyEnvelope(0, Instant.parse("2000-01-01T00:00:00Z"));
        InboxStatusQuery inbox = new InboxStatusQuery() {
            @Override
            public Collection<Envelope> findByStatus(EnvelopeStatus status) {
                throw new AssertionError("the whole folder must not be read");
            }

            @Override
            public Collection<Envelope> findByStatusModifiedBefore(EnvelopeStatus status, Instant limit) {
                askedStatuses.add(status);
                askedLimits.add(limit);
                return List.of(stuck);
            }
        };
        RepublishStuckEnvelopesStep step = new RepublishStuckEnvelopesStep(
            inbox,
            (keys, status) -> List.of(),
            new EventPublisher() {
                @Override public <E extends Event> void publish(E event) { published.add(event); }
            },
            Duration.ofMinutes(3),
            3,
            (value, attributes) -> {}
        );
        Instant before = Instant.now();

        step.apply(PipelineContext.empty());
        Instant after = Instant.now();

        assertThat(askedStatuses).containsExactly(EnvelopeStatus.CAPTURED);
        assertThat(askedLimits).singleElement().satisfies(limit ->
            assertThat(limit).isBetween(before.minus(Duration.ofMinutes(3)), after.minus(Duration.ofMinutes(3))));
        assertThat(published).hasSize(1);
    }

    private static Envelope anyEnvelope(int retries, Instant updatedAt) {
        IdempotencyKey key = IdempotencyKey.of("k-" + retries);
        return new Envelope(key, new EventMetadata(), MediaType.of("application/json"), "{}".getBytes(), EnvelopeStatus.CAPTURED, retries, updatedAt, updatedAt);
    }
}
