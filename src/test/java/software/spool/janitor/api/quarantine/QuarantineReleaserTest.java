package software.spool.janitor.api.quarantine;

import org.junit.jupiter.api.Test;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.Event;
import software.spool.core.model.event.EnvelopeStored;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadata;
import software.spool.core.model.vo.EventMetadataKey;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.MediaType;
import software.spool.core.port.bus.EventPublisher;
import software.spool.janitor.api.fixture.FakeInbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuarantineReleaserTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-09-01T10:00:00Z");

    private final FakeInbox inbox = new FakeInbox();
    private final List<Event> published = new ArrayList<>();
    private final QuarantineReleaser releaser = QuarantineReleaser.using(inbox, inbox, inbox, new EventPublisher() {
        @Override
        public <E extends Event> void publish(E event) {
            published.add(event);
        }
    });

    @Test
    void quarantined_listsOnlyWhatIsInQuarantine() {
        inbox.with(envelope("stuck", EnvelopeStatus.QUARANTINED, 3),
                envelope("healthy", EnvelopeStatus.CAPTURED, 0),
                envelope("done", EnvelopeStatus.PERSISTED, 0));

        Collection<Envelope> found = releaser.quarantined();

        assertThat(found).extracting(envelope -> envelope.idempotencyKey().value()).containsExactly("stuck");
    }

    @Test
    void requeue_aQuarantinedEnvelope_returnsItToCapturedWithNoRetriesAndPublishesItsEvent() {
        inbox.with(envelope("stuck", EnvelopeStatus.QUARANTINED, 3));

        ReleaseResult result = releaser.requeue(List.of(IdempotencyKey.of("stuck")));

        assertThat(result.released()).containsExactly(IdempotencyKey.of("stuck"));
        assertThat(result.skipped()).isEmpty();
        Envelope stored = inbox.stored("stuck").orElseThrow();
        assertThat(stored.status()).isEqualTo(EnvelopeStatus.CAPTURED);
        assertThat(stored.retries()).isZero();
        assertThat(stored.capturedAt()).isEqualTo(CAPTURED_AT);
        assertThat(published).singleElement().isInstanceOfSatisfying(EnvelopeStored.class, event -> {
            assertThat(event.idempotencyKey()).isEqualTo(IdempotencyKey.of("stuck"));
            assertThat(event.correlationId()).isEqualTo("correlation-stuck");
        });
    }

    @Test
    void requeue_keepsThePayloadOfTheEnvelope() {
        inbox.with(envelope("stuck", EnvelopeStatus.QUARANTINED, 3));

        releaser.requeue(List.of(IdempotencyKey.of("stuck")));

        assertThat(inbox.stored("stuck").orElseThrow().payload()).isEqualTo("{\"id\":1}".getBytes());
    }

    @Test
    void requeue_anEnvelopeThatIsNotQuarantined_isSkippedAndLeftAlone() {
        inbox.with(envelope("healthy", EnvelopeStatus.CAPTURED, 2));

        ReleaseResult result = releaser.requeue(List.of(IdempotencyKey.of("healthy")));

        assertThat(result.released()).isEmpty();
        assertThat(result.skipped()).containsExactly(IdempotencyKey.of("healthy"));
        assertThat(inbox.stored("healthy").orElseThrow().retries()).isEqualTo(2);
        assertThat(published).isEmpty();
    }

    @Test
    void requeue_aKeyThatDoesNotExist_isSkipped() {
        ReleaseResult result = releaser.requeue(List.of(IdempotencyKey.of("missing")));

        assertThat(result.released()).isEmpty();
        assertThat(result.skipped()).containsExactly(IdempotencyKey.of("missing"));
        assertThat(published).isEmpty();
    }

    @Test
    void requeue_severalKeysWithOneNotQuarantined_releasesOnlyTheQuarantinedOnes() {
        inbox.with(envelope("a", EnvelopeStatus.QUARANTINED, 3),
                envelope("b", EnvelopeStatus.PERSISTED, 0),
                envelope("c", EnvelopeStatus.QUARANTINED, 3));

        ReleaseResult result = releaser.requeue(List.of(IdempotencyKey.of("a"), IdempotencyKey.of("b"), IdempotencyKey.of("c")));

        assertThat(result.released()).containsExactly(IdempotencyKey.of("a"), IdempotencyKey.of("c"));
        assertThat(result.skipped()).containsExactly(IdempotencyKey.of("b"));
        assertThat(inbox.stored("b").orElseThrow().status()).isEqualTo(EnvelopeStatus.PERSISTED);
        assertThat(published).hasSize(2);
    }

    @Test
    void requeue_theSameKeyTwice_isReleasedOnce() {
        inbox.with(envelope("stuck", EnvelopeStatus.QUARANTINED, 3));

        ReleaseResult result = releaser.requeue(List.of(IdempotencyKey.of("stuck"), IdempotencyKey.of("stuck")));

        assertThat(result.released()).containsExactly(IdempotencyKey.of("stuck"));
        assertThat(published).hasSize(1);
    }

    @Test
    void requeue_noKeys_touchesNothingAndPublishesNothing() {
        inbox.with(envelope("stuck", EnvelopeStatus.QUARANTINED, 3));

        ReleaseResult result = releaser.requeue(List.of());

        assertThat(result.released()).isEmpty();
        assertThat(result.skipped()).isEmpty();
        assertThat(inbox.stored("stuck").orElseThrow().status()).isEqualTo(EnvelopeStatus.QUARANTINED);
        assertThat(published).isEmpty();
    }

    @Test
    void discard_aQuarantinedEnvelope_removesItAndFreesItsKey() {
        inbox.with(envelope("stuck", EnvelopeStatus.QUARANTINED, 3));

        ReleaseResult result = releaser.discard(List.of(IdempotencyKey.of("stuck")));

        assertThat(result.released()).containsExactly(IdempotencyKey.of("stuck"));
        assertThat(inbox.stored("stuck")).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void discard_anEnvelopeThatIsNotQuarantined_isSkippedAndKept() {
        inbox.with(envelope("healthy", EnvelopeStatus.CAPTURED, 0));

        ReleaseResult result = releaser.discard(List.of(IdempotencyKey.of("healthy")));

        assertThat(result.released()).isEmpty();
        assertThat(result.skipped()).containsExactly(IdempotencyKey.of("healthy"));
        assertThat(inbox.stored("healthy")).isPresent();
    }

    private static Envelope envelope(String key, EnvelopeStatus status, int retries) {
        EventMetadata metadata = new EventMetadata().set(EventMetadataKey.CORRELATION_ID, "correlation-" + key);
        return new Envelope(IdempotencyKey.of(key), metadata, MediaType.of("application/json"), "{\"id\":1}".getBytes(),
                status, retries, CAPTURED_AT, CAPTURED_AT.plusSeconds(60));
    }
}
