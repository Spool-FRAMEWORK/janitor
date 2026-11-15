package software.spool.janitor.internal.control;

import org.junit.jupiter.api.Test;
import software.spool.core.adapter.memory.InMemoryQuarantineRepository;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.failure.EnvelopeQuarantined;
import software.spool.core.model.quarantine.QuarantineLocation;
import software.spool.core.model.quarantine.QuarantineReason;
import software.spool.core.model.quarantine.QuarantineResolution;
import software.spool.core.model.quarantine.QuarantineStatus;
import software.spool.core.model.quarantine.QuarantinedRecord;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadata;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.MediaType;
import software.spool.core.pipeline.PipelineContext;
import software.spool.janitor.api.fixture.FakeInbox;

import javax.management.AttributeNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class QuarantineFailedEnvelopesStepTest {

    private static final IdempotencyKey KEY = IdempotencyKey.of("k1");

    private final FakeInbox inbox = new FakeInbox();
    private final InMemoryQuarantineRepository repository = new InMemoryQuarantineRepository();
    private final QuarantineFailedEnvelopesStep step = new QuarantineFailedEnvelopesStep(inbox, repository, (value, attributes) -> {});

    @Test
    void apply_envelopeInTheInbox_movesItToQuarantineAndKeepsItsRecord() throws AttributeNotFoundException {
        inbox.with(capturedEnvelope());
        Instant before = Instant.now();

        step.apply(cycleWith(quarantined(QuarantineReason.VALIDATION_FAILED, List.of("price must be positive", "id is required"))));

        assertThat(inbox.stored("k1")).map(Envelope::status).contains(EnvelopeStatus.QUARANTINED);
        QuarantinedRecord quarantinedRecord = repository.find(KEY).orElseThrow();
        assertThat(quarantinedRecord.envelope().idempotencyKey()).isEqualTo(KEY);
        assertThat(quarantinedRecord.envelope().payload()).isEqualTo("{\"price\":-1}".getBytes());
        assertThat(quarantinedRecord.location()).isEqualTo(QuarantineLocation.inbox());
        assertThat(quarantinedRecord.status()).isEqualTo(QuarantineStatus.PENDING);
        assertThat(quarantinedRecord.reason()).isEqualTo(QuarantineReason.VALIDATION_FAILED);
        assertThat(quarantinedRecord.violations()).containsExactly("price must be positive", "id is required");
        assertThat(quarantinedRecord.quarantinedAt()).isBetween(before, Instant.now());
    }

    @Test
    void apply_retriesExhausted_keepsTheRecordWithItsReasonAndNoViolations() throws AttributeNotFoundException {
        inbox.with(capturedEnvelope());

        step.apply(cycleWith(quarantined(QuarantineReason.RETRIES_EXHAUSTED, List.of())));

        QuarantinedRecord quarantinedRecord = repository.find(KEY).orElseThrow();
        assertThat(quarantinedRecord.reason()).isEqualTo(QuarantineReason.RETRIES_EXHAUSTED);
        assertThat(quarantinedRecord.violations()).isEmpty();
    }

    @Test
    void apply_keyWithAResolvedRecord_reopensItWithASecondEntry() throws AttributeNotFoundException {
        inbox.with(capturedEnvelope());
        repository.save(QuarantinedRecord.pending(capturedEnvelope(), QuarantineLocation.inbox(),
                QuarantineReason.VALIDATION_FAILED, List.of(), Instant.now()).resolve(QuarantineResolution.republished(Instant.now())));

        step.apply(cycleWith(quarantined(QuarantineReason.RETRIES_EXHAUSTED, List.of())));

        QuarantinedRecord reopened = repository.find(KEY).orElseThrow();
        assertThat(reopened.status()).isEqualTo(QuarantineStatus.PENDING);
        assertThat(reopened.reason()).isEqualTo(QuarantineReason.RETRIES_EXHAUSTED);
        assertThat(reopened.history()).hasSize(2);
    }

    @Test
    void apply_envelopeNoLongerInTheInbox_doesNothingAndDoesNotFail() {
        assertThatCode(() -> step.apply(cycleWith(quarantined(QuarantineReason.VALIDATION_FAILED, List.of("a")))))
                .doesNotThrowAnyException();

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void apply_noEvents_keepsNoRecord() throws AttributeNotFoundException {
        step.apply(cycleWith());

        assertThat(repository.findAll()).isEmpty();
    }

    private static PipelineContext cycleWith(EnvelopeQuarantined... events) {
        return PipelineContext.empty().with(JanitorScheduleKeys.ENVELOPES_QUARANTINED, List.of(events));
    }

    private static EnvelopeQuarantined quarantined(QuarantineReason reason, List<String> violations) {
        return EnvelopeQuarantined.builder().idempotencyKey(KEY).reason(reason).violations(violations).build();
    }

    private static Envelope capturedEnvelope() {
        Instant capturedAt = Instant.now().minus(Duration.ofHours(1));
        return new Envelope(KEY, new EventMetadata(), MediaType.of("application/json"), "{\"price\":-1}".getBytes(),
                EnvelopeStatus.CAPTURED, 0, capturedAt, capturedAt);
    }
}
