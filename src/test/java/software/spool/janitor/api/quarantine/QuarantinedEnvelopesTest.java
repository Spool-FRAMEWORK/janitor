package software.spool.janitor.api.quarantine;

import org.junit.jupiter.api.Test;
import software.spool.core.adapter.memory.InMemoryDataLake;
import software.spool.core.adapter.memory.InMemoryQuarantineRepository;
import software.spool.core.exception.DataLakeWriteException;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.Event;
import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.event.EnvelopePurged;
import software.spool.core.model.event.EnvelopeRepublished;
import software.spool.core.model.event.EnvelopeStored;
import software.spool.core.model.quarantine.QuarantineLocation;
import software.spool.core.model.quarantine.QuarantineReason;
import software.spool.core.model.quarantine.QuarantineResolution;
import software.spool.core.model.quarantine.QuarantineStatus;
import software.spool.core.model.quarantine.QuarantinedRecord;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadata;
import software.spool.core.model.vo.EventMetadataKey;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.MediaType;
import software.spool.core.model.vo.PartitionKey;
import software.spool.core.port.bus.EventPublisher;
import software.spool.janitor.api.fixture.FakeInbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuarantinedEnvelopesTest {

    private static final IdempotencyKey KEY = IdempotencyKey.of("k1");
    private static final byte[] CORRECTED_PAYLOAD = "{\"price\":10}".getBytes(StandardCharsets.UTF_8);
    private static final String BYPASS_REASON = "price rule misconfigured";

    private final FakeInbox inbox = new FakeInbox();
    private static final PartitionKey PARTITION = PartitionKey.ofEntriesWithoutDate().with("year", "2026")
            .with("source", "boe").build();

    private final InMemoryQuarantineRepository repository = new InMemoryQuarantineRepository();
    private final InMemoryDataLake dataLake = new InMemoryDataLake();
    private final List<Event> published = new ArrayList<>();
    private final EventPublisher publisher = new EventPublisher() {
        @Override
        public <E extends Event> void publish(E event) {
            published.add(event);
        }
    };
    private final QuarantinedEnvelopes quarantinedEnvelopes = QuarantinedEnvelopes.of(inbox, inbox, dataLake, repository, publisher);

    @Test
    void republish_quarantinedEnvelope_returnsItToCapturedWithoutRetries() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));

        assertThat(quarantinedEnvelopes.republish(List.of(KEY))).containsExactly(KEY);

        Envelope republished = inbox.stored("k1").orElseThrow();
        assertThat(republished.status()).isEqualTo(EnvelopeStatus.CAPTURED);
        assertThat(republished.retries()).isZero();
    }

    @Test
    void republish_envelopeTheTtlAlreadyRemovedFromTheInbox_writesItBackAsCaptured() {
        repository.save(pendingRecord(envelope(EnvelopeStatus.QUARANTINED), QuarantineLocation.inbox()));

        assertThat(quarantinedEnvelopes.republish(List.of(KEY))).containsExactly(KEY);

        Envelope republished = inbox.stored("k1").orElseThrow();
        assertThat(republished.status()).isEqualTo(EnvelopeStatus.CAPTURED);
        assertThat(republished.retries()).isZero();
        assertThat(republished.payload()).isEqualTo("{\"price\":-1}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void republish_quarantinedEnvelope_marksItsRecordRepublishedAndPublishesStoredAndRepublished() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));

        quarantinedEnvelopes.republish(List.of(KEY));

        QuarantinedRecord quarantinedRecord = repository.find(KEY).orElseThrow();
        assertThat(quarantinedRecord.status()).isEqualTo(QuarantineStatus.REPUBLISHED);
        assertThat(quarantinedRecord.history()).hasSize(1);
        assertThat(published).hasSize(2);
        assertThat(published.get(0)).isInstanceOfSatisfying(EnvelopeStored.class,
                event -> assertThat(event.idempotencyKey()).isEqualTo(KEY));
        assertThat(published.get(1)).isInstanceOfSatisfying(EnvelopeRepublished.class, event -> {
            assertThat(event.idempotencyKey()).isEqualTo(KEY);
            assertThat(event.republishedIdempotencyKey()).isEqualTo(KEY);
            assertThat(event.resolution()).isEqualTo(EnvelopeRepublished.Resolution.UNCHANGED);
        });
    }

    @Test
    void republish_withCorrectedPayload_writesANewEnvelopeWithTheRecalculatedKey() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));
        IdempotencyKey correctedKey = IdempotencyKey.of("boe", CORRECTED_PAYLOAD);

        Optional<IdempotencyKey> republished = quarantinedEnvelopes.republish(KEY, CORRECTED_PAYLOAD);

        assertThat(republished).contains(correctedKey);
        Envelope corrected = inbox.stored(correctedKey.value()).orElseThrow();
        assertThat(corrected.status()).isEqualTo(EnvelopeStatus.CAPTURED);
        assertThat(corrected.retries()).isZero();
        assertThat(corrected.payload()).isEqualTo(CORRECTED_PAYLOAD);
        assertThat(corrected.metadata().get(EventMetadataKey.SOURCE)).isEqualTo("boe");
        assertThat(corrected.metadata().get(EventMetadataKey.CORRECTED_FROM)).isEqualTo("k1");
    }

    @Test
    void republish_withCorrectedPayload_removesTheOriginalMarksItsRecordCorrectedAndPublishesTheNewKey() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));
        IdempotencyKey correctedKey = IdempotencyKey.of("boe", CORRECTED_PAYLOAD);

        quarantinedEnvelopes.republish(KEY, CORRECTED_PAYLOAD);

        assertThat(inbox.stored("k1")).isEmpty();
        assertThat(repository.find(KEY).orElseThrow().resolution())
                .isInstanceOfSatisfying(QuarantineResolution.Corrected.class,
                        corrected -> assertThat(corrected.correctedTo()).isEqualTo(correctedKey));
        assertThat(published).hasSize(2);
        assertThat(published.get(0)).isInstanceOfSatisfying(EnvelopeStored.class,
                event -> assertThat(event.idempotencyKey()).isEqualTo(correctedKey));
        assertThat(published.get(1)).isInstanceOfSatisfying(EnvelopeRepublished.class, event -> {
            assertThat(event.idempotencyKey()).isEqualTo(KEY);
            assertThat(event.republishedIdempotencyKey()).isEqualTo(correctedKey);
            assertThat(event.resolution()).isEqualTo(EnvelopeRepublished.Resolution.CORRECTED);
        });
    }

    @Test
    void purge_quarantinedEnvelope_deletesItMarksItsRecordPurgedAndPublishesPurged() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));

        assertThat(quarantinedEnvelopes.purge(List.of(KEY))).containsExactly(KEY);

        assertThat(inbox.stored("k1")).isEmpty();
        assertThat(repository.find(KEY)).map(QuarantinedRecord::status).contains(QuarantineStatus.PURGED);
        assertThat(published).singleElement().isInstanceOfSatisfying(EnvelopePurged.class,
                event -> assertThat(event.idempotencyKey()).isEqualTo(KEY));
    }

    @Test
    void republishAndPurge_keyWithoutRecord_skipItAndPublishNothing() {
        Envelope captured = envelope(EnvelopeStatus.CAPTURED);
        inbox.with(captured);

        assertThat(quarantinedEnvelopes.republish(List.of(KEY))).isEmpty();
        assertThat(quarantinedEnvelopes.republish(KEY, CORRECTED_PAYLOAD)).isEmpty();
        assertThat(quarantinedEnvelopes.purge(List.of(KEY))).isEmpty();

        assertThat(inbox.stored("k1")).contains(captured);
        assertThat(published).isEmpty();
    }

    @Test
    void republishAndPurge_recordAlreadyResolved_skipItAndPublishNothing() {
        repository.save(pendingRecord(envelope(EnvelopeStatus.QUARANTINED), QuarantineLocation.inbox())
                .resolve(QuarantineResolution.purged(Instant.now())));

        assertThat(quarantinedEnvelopes.republish(List.of(KEY))).isEmpty();
        assertThat(quarantinedEnvelopes.purge(List.of(KEY))).isEmpty();

        assertThat(inbox.stored("k1")).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void republish_recordThatStayedInBronze_writesItBackToItsPartitionWithoutTouchingTheInbox() {
        Envelope persisted = envelope(EnvelopeStatus.PERSISTED);
        inbox.with(persisted);
        repository.save(pendingRecord(persisted, QuarantineLocation.bronze(PARTITION)));

        assertThat(quarantinedEnvelopes.republish(List.of(KEY))).containsExactly(KEY);

        assertThat(dataLake.find(PARTITION, KEY)).map(Envelope::payload).contains(persisted.payload());
        assertThat(inbox.stored("k1")).contains(persisted);
        assertThat(repository.find(KEY)).map(QuarantinedRecord::status).contains(QuarantineStatus.REPUBLISHED);
    }

    @Test
    void republish_recordThatStayedInBronze_publishesPersistedForItsPartitionAndRepublished() {
        repository.save(pendingRecord(envelope(EnvelopeStatus.PERSISTED), QuarantineLocation.bronze(PARTITION)));

        quarantinedEnvelopes.republish(List.of(KEY));

        assertThat(published).hasSize(2);
        assertThat(published.get(0)).isInstanceOfSatisfying(EnvelopePersisted.class, event -> {
            assertThat(event.idempotencyKey()).isEqualTo(KEY);
            assertThat(event.partitionKey()).isEqualTo(PARTITION);
        });
        assertThat(published.get(1)).isInstanceOfSatisfying(EnvelopeRepublished.class,
                event -> assertThat(event.wasCorrected()).isFalse());
    }

    @Test
    void republish_recordThatStayedInBronzeAndTheDataLakeDoesNotWrite_failsAndLeavesItPending() {
        QuarantinedEnvelopes withADataLakeThatDoesNotWrite = QuarantinedEnvelopes.of(inbox, inbox, items -> Stream.empty(),
                repository, publisher);
        repository.save(pendingRecord(envelope(EnvelopeStatus.PERSISTED), QuarantineLocation.bronze(PARTITION)));

        assertThatThrownBy(() -> withADataLakeThatDoesNotWrite.republish(List.of(KEY)))
                .isInstanceOf(DataLakeWriteException.class)
                .hasMessageContaining("k1");
        assertThat(repository.find(KEY)).map(QuarantinedRecord::status).contains(QuarantineStatus.PENDING);
        assertThat(published).isEmpty();
    }

    @Test
    void republish_correctedRecordThatStayedInBronze_writesTheNewEnvelopeToTheSamePartition() {
        repository.save(pendingRecord(envelope(EnvelopeStatus.PERSISTED), QuarantineLocation.bronze(PARTITION)));
        IdempotencyKey correctedKey = IdempotencyKey.of("boe", CORRECTED_PAYLOAD);

        assertThat(quarantinedEnvelopes.republish(KEY, CORRECTED_PAYLOAD)).contains(correctedKey);

        Envelope corrected = dataLake.find(PARTITION, correctedKey).orElseThrow();
        assertThat(corrected.payload()).isEqualTo(CORRECTED_PAYLOAD);
        assertThat(corrected.status()).isEqualTo(EnvelopeStatus.PERSISTED);
        assertThat(corrected.metadata().get(EventMetadataKey.CORRECTED_FROM)).isEqualTo("k1");
        assertThat(dataLake.find(PARTITION, KEY)).isEmpty();
        assertThat(inbox.stored(correctedKey.value())).isEmpty();
        assertThat(repository.find(KEY).orElseThrow().resolution()).isInstanceOf(QuarantineResolution.Corrected.class);
        assertThat(published.get(0)).isInstanceOfSatisfying(EnvelopePersisted.class, event -> {
            assertThat(event.idempotencyKey()).isEqualTo(correctedKey);
            assertThat(event.partitionKey()).isEqualTo(PARTITION);
        });
    }

    @Test
    void purge_recordThatStayedInBronze_marksItPurgedWithoutTouchingTheInbox() {
        Envelope persisted = envelope(EnvelopeStatus.PERSISTED);
        inbox.with(persisted);
        repository.save(pendingRecord(persisted, QuarantineLocation.bronze(PARTITION)));

        assertThat(quarantinedEnvelopes.purge(List.of(KEY))).containsExactly(KEY);

        assertThat(inbox.stored("k1")).contains(persisted);
        assertThat(repository.find(KEY)).map(QuarantinedRecord::status).contains(QuarantineStatus.PURGED);
        assertThat(published).singleElement().isInstanceOf(EnvelopePurged.class);
    }

    @Test
    void republishAndPurge_recordThatStayedInBronzeWithoutADataLakeWriter_skipItAndLeaveItPending() {
        QuarantinedEnvelopes withoutBronze = QuarantinedEnvelopes.of(inbox, inbox, repository, publisher);
        repository.save(pendingRecord(envelope(EnvelopeStatus.PERSISTED), QuarantineLocation.bronze(PARTITION)));

        assertThat(withoutBronze.republish(List.of(KEY))).isEmpty();
        assertThat(withoutBronze.republish(KEY, CORRECTED_PAYLOAD)).isEmpty();
        assertThat(withoutBronze.purge(List.of(KEY))).isEmpty();

        assertThat(inbox.stored("k1")).isEmpty();
        assertThat(repository.find(KEY)).map(QuarantinedRecord::status).contains(QuarantineStatus.PENDING);
        assertThat(published).isEmpty();
    }

    @Test
    void bypass_recordThatStayedInTheInbox_returnsItAsCapturedWithTheMark() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));

        assertThat(quarantinedEnvelopes.bypass(KEY, BYPASS_REASON)).contains(KEY);

        Envelope bypassed = inbox.stored("k1").orElseThrow();
        assertThat(bypassed.status()).isEqualTo(EnvelopeStatus.CAPTURED);
        assertThat(bypassed.retries()).isZero();
        assertThat(bypassed.payload()).isEqualTo("{\"price\":-1}".getBytes(StandardCharsets.UTF_8));
        assertThat(bypassed.metadata().get(EventMetadataKey.VALIDATION_BYPASS)).isEqualTo(BYPASS_REASON);
    }

    @Test
    void bypass_recordThatStayedInTheInbox_marksItBypassedWithItsReasonAndPublishesStoredAndRepublished() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));

        quarantinedEnvelopes.bypass(KEY, BYPASS_REASON);

        assertThat(repository.find(KEY).orElseThrow().resolution())
                .isInstanceOfSatisfying(QuarantineResolution.Bypassed.class,
                        bypassed -> assertThat(bypassed.reason()).isEqualTo(BYPASS_REASON));
        assertThat(published).hasSize(2);
        assertThat(published.get(0)).isInstanceOf(EnvelopeStored.class);
        assertThat(published.get(1)).isInstanceOfSatisfying(EnvelopeRepublished.class, event -> {
            assertThat(event.republishedIdempotencyKey()).isEqualTo(KEY);
            assertThat(event.resolution()).isEqualTo(EnvelopeRepublished.Resolution.BYPASSED);
        });
    }

    @Test
    void bypass_recordThatStayedInBronze_writesItBackToItsPartitionWithTheMarkWithoutTouchingTheInbox() {
        Envelope persisted = envelope(EnvelopeStatus.PERSISTED);
        inbox.with(persisted);
        repository.save(pendingRecord(persisted, QuarantineLocation.bronze(PARTITION)));

        assertThat(quarantinedEnvelopes.bypass(KEY, BYPASS_REASON)).contains(KEY);

        Envelope bypassed = dataLake.find(PARTITION, KEY).orElseThrow();
        assertThat(bypassed.status()).isEqualTo(EnvelopeStatus.PERSISTED);
        assertThat(bypassed.metadata().get(EventMetadataKey.VALIDATION_BYPASS)).isEqualTo(BYPASS_REASON);
        assertThat(inbox.stored("k1")).contains(persisted);
        assertThat(repository.find(KEY)).map(QuarantinedRecord::status).contains(QuarantineStatus.BYPASSED);
        assertThat(published.get(0)).isInstanceOfSatisfying(EnvelopePersisted.class,
                event -> assertThat(event.partitionKey()).isEqualTo(PARTITION));
        assertThat(published.get(1)).isInstanceOfSatisfying(EnvelopeRepublished.class,
                event -> assertThat(event.resolution()).isEqualTo(EnvelopeRepublished.Resolution.BYPASSED));
    }

    @Test
    void bypass_blankReason_failsAndLeavesTheRecordPending() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));

        assertThatThrownBy(() -> quarantinedEnvelopes.bypass(KEY, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");

        assertThat(repository.find(KEY)).map(QuarantinedRecord::status).contains(QuarantineStatus.PENDING);
        assertThat(published).isEmpty();
    }

    @Test
    void bypass_keyWithoutPendingRecord_skipsItAndPublishesNothing() {
        assertThat(quarantinedEnvelopes.bypass(KEY, BYPASS_REASON)).isEmpty();

        assertThat(published).isEmpty();
    }

    private void inQuarantine(Envelope envelope) {
        inbox.with(envelope);
        repository.save(pendingRecord(envelope, QuarantineLocation.inbox()));
    }

    private static QuarantinedRecord pendingRecord(Envelope envelope, QuarantineLocation location) {
        return QuarantinedRecord.pending(envelope, location, QuarantineReason.VALIDATION_FAILED,
                List.of("price must be positive"), Instant.now());
    }

    private static Envelope envelope(EnvelopeStatus status) {
        Instant capturedAt = Instant.now().minus(Duration.ofHours(1));
        return new Envelope(KEY, new EventMetadata().set(EventMetadataKey.SOURCE, "boe"), MediaType.of("application/json"),
                "{\"price\":-1}".getBytes(StandardCharsets.UTF_8), status, 2, capturedAt, capturedAt);
    }
}
