package software.spool.janitor.api.builder;

import software.spool.core.model.quarantine.Resolver;
import org.junit.jupiter.api.Test;
import software.spool.core.adapter.memory.InMemoryDataLake;
import software.spool.core.adapter.memory.InMemoryQuarantineRepository;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.Event;
import software.spool.core.model.event.EnvelopePurged;
import software.spool.core.model.quarantine.QuarantineLocation;
import software.spool.core.model.quarantine.QuarantineReason;
import software.spool.core.model.quarantine.QuarantineResolution;
import software.spool.core.model.quarantine.QuarantineStatus;
import software.spool.core.model.quarantine.QuarantinedRecord;
import software.spool.core.model.schedule.Schedule;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadata;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.MediaType;
import software.spool.core.model.vo.PartitionKey;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.pipeline.Result;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.inbox.InboxEnvelopeRemover;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.janitor.api.Janitor;
import software.spool.janitor.internal.control.JanitorScheduleKeys;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void create_withZeroInboxUpdateAttempts_failsNamingTheSetting() {
        assertThatThrownBy(() -> builder().withMaxInboxUpdateAttempts(0).create())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInboxUpdateAttempts")
                .hasMessageContaining("0");
    }

    @Test
    void create_withoutQuarantineRepository_failsWithAClearMessage() {
        PollingJanitorBuilder builder = JanitorBuilderFactory.watchdog(null, "janitor-test")
                .polling()
                .from(status -> List.of())
                .with(mock(InboxUpdater.class))
                .removeWith(mock(InboxEnvelopeRemover.class))
                .on(mock(EventPublisher.class))
                .subscribeWith(mock(EventSubscriber.class))
                .schedule(Schedule.every(Duration.ofSeconds(1)));

        assertThatThrownBy(builder::create)
                .isInstanceOf(NullPointerException.class)
                .hasMessage("QuarantineRepository required");
    }

    @Test
    void pipeline_withAQuarantineTtl_removesTheEnvelopeFromTheInboxButKeepsItsPendingRecord() {
        InMemoryQuarantineRepository repository = new InMemoryQuarantineRepository();
        Envelope quarantinedEnvelope = envelope("quarantined");
        repository.save(QuarantinedRecord.pending(quarantinedEnvelope, QuarantineLocation.inbox(), QuarantineReason.RETRIES_EXHAUSTED,
                List.of(), Instant.now()));
        List<IdempotencyKey> removed = new ArrayList<>();
        List<Event> published = new ArrayList<>();
        PollingJanitorBuilder builder = builder(inboxHolding(EnvelopeStatus.QUARANTINED, quarantinedEnvelope, new ArrayList<>(), new ArrayList<>()))
                .quarantineIn(repository)
                .removeWith(keys -> { removed.addAll(keys); return List.of(); })
                .on(new EventPublisher() {
                    @Override
                    public <E extends Event> void publish(E event) {
                        published.add(event);
                    }
                })
                .withMillisecondsQuarantineTtl(3_600_000);

        builder.initializePipeline((value, attributes) -> {}).execute(emptyCycle());

        assertThat(removed).containsExactly(quarantinedEnvelope.idempotencyKey());
        assertThat(repository.find(quarantinedEnvelope.idempotencyKey())).map(QuarantinedRecord::status).contains(QuarantineStatus.PENDING);
        assertThat(published).noneMatch(EnvelopePurged.class::isInstance);
    }

    @Test
    void pipeline_withAQuarantineRetention_deletesOnlyTheRecordsResolvedLongerAgo() {
        InMemoryQuarantineRepository repository = new InMemoryQuarantineRepository();
        Instant longAgo = Instant.now().minus(Duration.ofDays(60));
        repository.save(record("resolved-long-ago", longAgo).resolve(QuarantineResolution.purged(longAgo, Resolver.identified("ana", "people"))));
        repository.save(record("resolved-recently", longAgo).resolve(QuarantineResolution.republished(Instant.now(), Resolver.identified("ana", "people"))));
        repository.save(record("pending-long-ago", longAgo));
        PollingJanitorBuilder builder = builder()
                .quarantineIn(repository)
                .withMillisecondsQuarantineRetention(Duration.ofDays(30).toMillis());

        builder.initializePipeline((value, attributes) -> {}).execute(emptyCycle());

        assertThat(repository.findAll()).extracting(quarantinedRecord -> quarantinedRecord.idempotencyKey().value())
                .containsExactlyInAnyOrder("resolved-recently", "pending-long-ago");
    }

    @Test
    void pipeline_withoutAQuarantineRetention_keepsTheResolvedRecords() {
        InMemoryQuarantineRepository repository = new InMemoryQuarantineRepository();
        Instant longAgo = Instant.now().minus(Duration.ofDays(3650));
        repository.save(record("resolved-long-ago", longAgo).resolve(QuarantineResolution.purged(longAgo, Resolver.identified("ana", "people"))));

        builder().quarantineIn(repository).initializePipeline((value, attributes) -> {}).execute(emptyCycle());

        assertThat(repository.findAll()).hasSize(1);
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

    @Test
    void create_withADataLakeWriter_givesQuarantinedEnvelopesThatRewriteTheDataLake() {
        InMemoryQuarantineRepository repository = new InMemoryQuarantineRepository();
        InMemoryDataLake dataLake = new InMemoryDataLake();
        PartitionKey partition = PartitionKey.ofEntriesWithoutDate().with("source", "boe").build();
        Envelope persisted = envelope("rejected-by-the-mounter");
        repository.save(QuarantinedRecord.pending(persisted, QuarantineLocation.bronze(partition), QuarantineReason.VALIDATION_FAILED,
                List.of(), Instant.now()));

        Janitor janitor = builder().quarantineIn(repository).rewriteDataLakeWith(dataLake).create();

        assertThat(janitor.quarantinedEnvelopes().republish(List.of(persisted.idempotencyKey()), Resolver.identified("ana", "people"))).containsExactly(persisted.idempotencyKey());
        assertThat(dataLake.find(partition, persisted.idempotencyKey())).isPresent();
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
                return asked == status ? List.of(held) : List.of();
            }

            @Override
            public Collection<Envelope> findByStatusModifiedBefore(EnvelopeStatus asked, Instant limit) {
                askedStatuses.add(asked);
                askedLimits.add(limit);
                return asked == status ? List.of(held) : List.of();
            }
        };
    }

    private static QuarantinedRecord record(String key, Instant quarantinedAt) {
        return QuarantinedRecord.pending(envelope(key), QuarantineLocation.inbox(), QuarantineReason.VALIDATION_FAILED, List.of(), quarantinedAt);
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
                .quarantineIn(new InMemoryQuarantineRepository())
                .schedule(Schedule.every(Duration.ofSeconds(1)));
    }
}
