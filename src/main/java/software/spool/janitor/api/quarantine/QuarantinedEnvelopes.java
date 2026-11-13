package software.spool.janitor.api.quarantine;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.event.EnvelopePurged;
import software.spool.core.model.event.EnvelopeRepublished;
import software.spool.core.model.event.EnvelopeStored;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadata;
import software.spool.core.model.vo.EventMetadataKey;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.inbox.InboxEnvelopeRemover;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.logging.Logger;
import software.spool.core.port.quarantine.QuarantineRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The envelopes of an inbox that are in quarantine, and what a person can do with them.
 *
 * <p>
 * The quarantine is read in full to know what is in it, which is fine for a place that should hold few envelopes.
 * </p>
 */
public final class QuarantinedEnvelopes implements QuarantinedEnvelopeRepublisher, QuarantinedEnvelopePurger {
    private static final Logger LOG = LoggerFactory.getLogger(QuarantinedEnvelopes.class);

    private final InboxStatusQuery reader;
    private final InboxUpdater updater;
    private final InboxEnvelopeRemover remover;
    private final QuarantineRepository quarantineRepository;
    private final EventPublisher publisher;

    private QuarantinedEnvelopes(InboxStatusQuery reader, InboxUpdater updater, InboxEnvelopeRemover remover,
                                 QuarantineRepository quarantineRepository, EventPublisher publisher) {
        this.reader = reader;
        this.updater = updater;
        this.remover = remover;
        this.quarantineRepository = quarantineRepository;
        this.publisher = publisher;
    }

    public static QuarantinedEnvelopes of(InboxStatusQuery reader, InboxUpdater updater, InboxEnvelopeRemover remover,
                                          QuarantineRepository quarantineRepository, EventPublisher publisher) {
        return new QuarantinedEnvelopes(reader, updater, remover, quarantineRepository, publisher);
    }

    @Override
    public Collection<IdempotencyKey> republish(Collection<IdempotencyKey> idempotencyKeys) {
        List<Envelope> quarantined = inQuarantine(idempotencyKeys);
        if (quarantined.isEmpty()) return List.of();
        Collection<Envelope> moved = updater.update(keysOf(quarantined), EnvelopeStatus.CAPTURED);
        moved.forEach(envelope -> {
            updater.update(withoutRetries(envelope));
            quarantineRepository.delete(envelope.idempotencyKey());
            publisher.publish(storedEventOf(envelope, envelope.idempotencyKey()));
            publisher.publish(republishedEventOf(envelope, envelope.idempotencyKey()));
        });
        LOG.info("Republished {} envelopes from quarantine", moved.size());
        return keysOf(moved);
    }

    @Override
    public Optional<IdempotencyKey> republish(IdempotencyKey idempotencyKey, byte[] correctedPayload) {
        Optional<Envelope> quarantined = inQuarantine(List.of(idempotencyKey)).stream().findFirst();
        if (quarantined.isEmpty()) return Optional.empty();
        Envelope original = quarantined.get();
        IdempotencyKey correctedKey = IdempotencyKey.of(sourceOf(original), correctedPayload);
        if (correctedKey.equals(idempotencyKey)) {
            return republish(List.of(idempotencyKey)).stream().findFirst();
        }
        Instant now = Instant.now();
        updater.update(new Envelope(correctedKey, correctedMetadataOf(original), original.mediaType(), correctedPayload,
                EnvelopeStatus.CAPTURED, 0, now, now));
        remover.remove(List.of(idempotencyKey));
        quarantineRepository.delete(idempotencyKey);
        publisher.publish(storedEventOf(original, correctedKey));
        publisher.publish(republishedEventOf(original, correctedKey));
        LOG.info("Republished {} from quarantine as {} with a corrected payload", idempotencyKey.value(), correctedKey.value());
        return Optional.of(correctedKey);
    }

    @Override
    public Collection<IdempotencyKey> purge(Collection<IdempotencyKey> idempotencyKeys) {
        List<Envelope> quarantined = inQuarantine(idempotencyKeys);
        if (quarantined.isEmpty()) return List.of();
        List<IdempotencyKey> keys = keysOf(quarantined);
        remover.remove(keys);
        quarantined.forEach(envelope -> {
            quarantineRepository.delete(envelope.idempotencyKey());
            publisher.publish(EnvelopePurged.builder()
                    .correlationId(envelope.metadata().get(EventMetadataKey.CORRELATION_ID))
                    .idempotencyKey(envelope.idempotencyKey())
                    .build());
        });
        LOG.warn("Purged {} envelopes from quarantine", keys.size());
        return keys;
    }

    private List<Envelope> inQuarantine(Collection<IdempotencyKey> idempotencyKeys) {
        Map<IdempotencyKey, Envelope> quarantined = reader.findByStatus(EnvelopeStatus.QUARANTINED).stream()
                .collect(Collectors.toMap(Envelope::idempotencyKey, Function.identity(), (first, second) -> second));
        List<Envelope> selected = new ArrayList<>();
        for (IdempotencyKey idempotencyKey : new LinkedHashSet<>(idempotencyKeys)) {
            Optional<Envelope> envelope = Optional.ofNullable(quarantined.get(idempotencyKey));
            envelope.ifPresentOrElse(selected::add,
                    () -> LOG.warn("Skipped {}: it is not in quarantine", idempotencyKey.value()));
        }
        return selected;
    }

    private static List<IdempotencyKey> keysOf(Collection<Envelope> envelopes) {
        return envelopes.stream().map(Envelope::idempotencyKey).toList();
    }

    private static String sourceOf(Envelope envelope) {
        return Optional.ofNullable(envelope.metadata().get(EventMetadataKey.SOURCE))
                .orElseThrow(() -> new IllegalStateException("Envelope " + envelope.idempotencyKey().value()
                        + " has no " + EventMetadataKey.SOURCE + " metadata, so the key of its corrected payload cannot be calculated"));
    }

    private static EventMetadata correctedMetadataOf(Envelope original) {
        return new EventMetadata(new HashMap<>(original.metadata().metadata()))
                .set(EventMetadataKey.CORRECTED_FROM, original.idempotencyKey().value());
    }

    private static Envelope withoutRetries(Envelope envelope) {
        return new Envelope(envelope.idempotencyKey(), envelope.metadata(), envelope.mediaType(), envelope.payload(),
                EnvelopeStatus.CAPTURED, 0, envelope.capturedAt(), envelope.updatedAt());
    }

    private static EnvelopeStored storedEventOf(Envelope original, IdempotencyKey republishedKey) {
        return EnvelopeStored.builder()
                .correlationId(original.metadata().get(EventMetadataKey.CORRELATION_ID))
                .idempotencyKey(republishedKey)
                .build();
    }

    private static EnvelopeRepublished republishedEventOf(Envelope original, IdempotencyKey republishedKey) {
        return EnvelopeRepublished.builder()
                .correlationId(original.metadata().get(EventMetadataKey.CORRELATION_ID))
                .idempotencyKey(original.idempotencyKey())
                .republishedIdempotencyKey(republishedKey)
                .build();
    }
}
