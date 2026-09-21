package software.spool.janitor.api.builder;

import org.junit.jupiter.api.Test;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadata;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.MediaType;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.pipeline.Result;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.inbox.InboxEnvelopeRemover;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.janitor.internal.control.JanitorScheduleKeys;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class PollingJanitorBuilderTest {

    @Test
    void create_withoutThresholdNorTtl_usesTheDefaultsOfTheStepsInsteadOfFailing() {
        assertThatCode(() -> builder().create()).doesNotThrowAnyException();
    }

    @Test
    void create_withOnlyTheThreshold_leavesTheTtlToItsDefault() {
        assertThatCode(() -> builder().withMillisecondsThreshold(60_000).create()).doesNotThrowAnyException();
    }

    @Test
    void create_withOnlyTheTtl_leavesTheThresholdToItsDefault() {
        assertThatCode(() -> builder().withMillisecondsTtl(86_400_000).create()).doesNotThrowAnyException();
    }

    @Test
    void create_withBothValues_works() {
        assertThatCode(() -> builder().withMillisecondsThreshold(60_000).withMillisecondsTtl(86_400_000).create())
                .doesNotThrowAnyException();
    }

    @Test
    void create_withAQuarantineTtl_works() {
        assertThatCode(() -> builder().withMillisecondsQuarantineTtl(604_800_000).create()).doesNotThrowAnyException();
    }

    @Test
    void pipeline_withAQuarantineTtl_removesWhatHasBeenQuarantinedForLonger() {
        List<EnvelopeStatus> askedStatuses = new ArrayList<>();
        List<Instant> askedLimits = new ArrayList<>();
        List<IdempotencyKey> removed = new ArrayList<>();
        Envelope quarantined = envelope("quarantined");
        PollingJanitorBuilder builder = builder(inboxHolding(EnvelopeStatus.QUARANTINED, quarantined, askedStatuses, askedLimits))
                .removeWith(keys -> { removed.addAll(keys); return List.of(); })
                .withMillisecondsQuarantineTtl(3_600_000);
        Instant before = Instant.now();

        Result<PipelineContext> result = builder.initializePipeline((value, attributes) -> {}).execute(emptyCycle());
        Instant after = Instant.now();

        assertThat(result).isInstanceOf(Result.Ok.class);
        int asked = askedStatuses.indexOf(EnvelopeStatus.QUARANTINED);
        assertThat(asked).isGreaterThanOrEqualTo(0);
        assertThat(askedLimits.get(asked)).isBetween(before.minus(Duration.ofHours(1)), after.minus(Duration.ofHours(1)));
        assertThat(removed).containsExactly(quarantined.idempotencyKey());
    }

    @Test
    void pipeline_withoutAQuarantineTtl_neverAsksForQuarantinedEnvelopes() {
        List<EnvelopeStatus> askedStatuses = new ArrayList<>();
        List<IdempotencyKey> removed = new ArrayList<>();
        PollingJanitorBuilder builder = builder(inboxHolding(EnvelopeStatus.QUARANTINED, envelope("quarantined"), askedStatuses, new ArrayList<>()))
                .removeWith(keys -> { removed.addAll(keys); return List.of(); });

        Result<PipelineContext> result = builder.initializePipeline((value, attributes) -> {}).execute(emptyCycle());

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(askedStatuses).doesNotContain(EnvelopeStatus.QUARANTINED);
        assertThat(removed).isEmpty();
    }

    private static PipelineContext emptyCycle() {
        return PipelineContext.empty()
                .with(JanitorScheduleKeys.ENVELOPES_PERSISTED, List.of())
                .with(JanitorScheduleKeys.ENVELOPES_QUARANTINED, List.of());
    }

    private static InboxStatusQuery inboxHolding(EnvelopeStatus status, Envelope held, List<EnvelopeStatus> askedStatuses, List<Instant> askedLimits) {
        return new InboxStatusQuery() {
            @Override
            public Collection<Envelope> findByStatus(EnvelopeStatus asked) {
                return List.of();
            }

            @Override
            public Collection<Envelope> findByStatusModifiedBefore(EnvelopeStatus asked, Instant limit) {
                askedStatuses.add(asked);
                askedLimits.add(limit);
                return asked == status ? List.of(held) : List.of();
            }
        };
    }

    private static Envelope envelope(String key) {
        Instant longAgo = Instant.parse("2000-01-01T00:00:00Z");
        return new Envelope(IdempotencyKey.of(key), new EventMetadata(), MediaType.of("application/json"), "{}".getBytes(), EnvelopeStatus.QUARANTINED, 3, longAgo, longAgo);
    }

    private static PollingJanitorBuilder builder() {
        return builder(status -> List.of());
    }

    private static PollingJanitorBuilder builder(InboxStatusQuery inbox) {
        return JanitorBuilderFactory.watchdog(null, "janitor-test")
                .polling()
                .from(inbox)
                .with(mock(InboxUpdater.class))
                .removeWith(mock(InboxEnvelopeRemover.class))
                .on(mock(EventPublisher.class))
                .subscribeWith(mock(EventSubscriber.class))
                .every(Duration.ofSeconds(1));
    }
}
