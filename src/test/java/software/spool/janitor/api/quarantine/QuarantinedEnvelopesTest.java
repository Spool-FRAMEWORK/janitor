package software.spool.janitor.api.quarantine;

import org.junit.jupiter.api.Test;
import software.spool.core.adapter.memory.InMemoryQuarantineRepository;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.Event;
import software.spool.core.model.event.EnvelopePurged;
import software.spool.core.model.event.EnvelopeRepublished;
import software.spool.core.model.event.EnvelopeStored;
import software.spool.core.model.quarantine.QuarantineReason;
import software.spool.core.model.quarantine.QuarantinedRecord;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadata;
import software.spool.core.model.vo.EventMetadataKey;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.MediaType;
import software.spool.core.port.bus.EventPublisher;
import software.spool.janitor.api.fixture.FakeInbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QuarantinedEnvelopesTest {

    private static final IdempotencyKey KEY = IdempotencyKey.of("k1");
    private static final byte[] CORRECTED_PAYLOAD = "{\"price\":10}".getBytes(StandardCharsets.UTF_8);

    private final FakeInbox inbox = new FakeInbox();
    private final InMemoryQuarantineRepository repository = new InMemoryQuarantineRepository();
    private final List<Event> published = new ArrayList<>();
    private final QuarantinedEnvelopes quarantinedEnvelopes = QuarantinedEnvelopes.of(inbox, inbox, inbox, repository,
            new EventPublisher() {
                @Override
                public <E extends Event> void publish(E event) {
                    published.add(event);
                }
            });

    @Test
    void republish_quarantinedEnvelope_returnsItToCapturedWithoutRetries() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));

        assertThat(quarantinedEnvelopes.republish(List.of(KEY))).containsExactly(KEY);

        Envelope republished = inbox.stored("k1").orElseThrow();
        assertThat(republished.status()).isEqualTo(EnvelopeStatus.CAPTURED);
        assertThat(republished.retries()).isZero();
    }

    @Test
    void republish_quarantinedEnvelope_deletesItsRecordAndPublishesStoredAndRepublished() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));

        quarantinedEnvelopes.republish(List.of(KEY));

        assertThat(repository.find(KEY)).isEmpty();
        assertThat(published).hasSize(2);
        assertThat(published.get(0)).isInstanceOfSatisfying(EnvelopeStored.class,
                event -> assertThat(event.idempotencyKey()).isEqualTo(KEY));
        assertThat(published.get(1)).isInstanceOfSatisfying(EnvelopeRepublished.class, event -> {
            assertThat(event.idempotencyKey()).isEqualTo(KEY);
            assertThat(event.republishedIdempotencyKey()).isEqualTo(KEY);
            assertThat(event.wasCorrected()).isFalse();
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
    void republish_withCorrectedPayload_removesTheOriginalAndItsRecordAndPublishesTheNewKey() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));
        IdempotencyKey correctedKey = IdempotencyKey.of("boe", CORRECTED_PAYLOAD);

        quarantinedEnvelopes.republish(KEY, CORRECTED_PAYLOAD);

        assertThat(inbox.stored("k1")).isEmpty();
        assertThat(repository.find(KEY)).isEmpty();
        assertThat(published).hasSize(2);
        assertThat(published.get(0)).isInstanceOfSatisfying(EnvelopeStored.class,
                event -> assertThat(event.idempotencyKey()).isEqualTo(correctedKey));
        assertThat(published.get(1)).isInstanceOfSatisfying(EnvelopeRepublished.class, event -> {
            assertThat(event.idempotencyKey()).isEqualTo(KEY);
            assertThat(event.republishedIdempotencyKey()).isEqualTo(correctedKey);
            assertThat(event.wasCorrected()).isTrue();
        });
    }

    @Test
    void purge_quarantinedEnvelope_deletesItAndItsRecordAndPublishesPurged() {
        inQuarantine(envelope(EnvelopeStatus.QUARANTINED));

        assertThat(quarantinedEnvelopes.purge(List.of(KEY))).containsExactly(KEY);

        assertThat(inbox.stored("k1")).isEmpty();
        assertThat(repository.find(KEY)).isEmpty();
        assertThat(published).singleElement().isInstanceOfSatisfying(EnvelopePurged.class,
                event -> assertThat(event.idempotencyKey()).isEqualTo(KEY));
    }

    @Test
    void republishAndPurge_envelopeNotInQuarantine_skipItAndPublishNothing() {
        Envelope captured = envelope(EnvelopeStatus.CAPTURED);
        inbox.with(captured);

        assertThat(quarantinedEnvelopes.republish(List.of(KEY))).isEmpty();
        assertThat(quarantinedEnvelopes.republish(KEY, CORRECTED_PAYLOAD)).isEmpty();
        assertThat(quarantinedEnvelopes.purge(List.of(KEY))).isEmpty();

        assertThat(inbox.stored("k1")).contains(captured);
        assertThat(published).isEmpty();
    }

    private void inQuarantine(Envelope envelope) {
        inbox.with(envelope);
        repository.save(new QuarantinedRecord(envelope, QuarantineReason.VALIDATION_FAILED, List.of("price must be positive"), Instant.now()));
    }

    private static Envelope envelope(EnvelopeStatus status) {
        Instant capturedAt = Instant.now().minus(Duration.ofHours(1));
        return new Envelope(KEY, new EventMetadata().set(EventMetadataKey.SOURCE, "boe"), MediaType.of("application/json"),
                "{\"price\":-1}".getBytes(StandardCharsets.UTF_8), status, 2, capturedAt, capturedAt);
    }
}
