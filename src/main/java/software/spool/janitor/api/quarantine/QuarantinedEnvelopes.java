package software.spool.janitor.api.quarantine;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.event.EnvelopePurged;
import software.spool.core.model.event.EnvelopeRepublished;
import software.spool.core.model.event.EnvelopeStored;
import software.spool.core.model.quarantine.QuarantineLocation;
import software.spool.core.model.quarantine.QuarantineResolution;
import software.spool.core.model.quarantine.QuarantineStatus;
import software.spool.core.model.quarantine.QuarantinedRecord;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadata;
import software.spool.core.model.vo.EventMetadataKey;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.inbox.InboxEnvelopeRemover;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.logging.Logger;
import software.spool.core.port.quarantine.QuarantineRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The envelopes in quarantine, and what a person can do with them.
 *
 * <p>
 * What is in quarantine comes from the quarantine records, not from the inbox: an envelope can be resolved even
 * after the inbox has removed it. Resolving a record marks how it ended instead of deleting it.
 * </p>
 */
public final class QuarantinedEnvelopes implements QuarantinedEnvelopeRepublisher, QuarantinedEnvelopePurger {
    private static final Logger LOG = LoggerFactory.getLogger(QuarantinedEnvelopes.class);

    private final InboxUpdater updater;
    private final InboxEnvelopeRemover remover;
    private final QuarantineRepository quarantineRepository;
    private final EventPublisher publisher;

    private QuarantinedEnvelopes(InboxUpdater updater, InboxEnvelopeRemover remover,
                                 QuarantineRepository quarantineRepository, EventPublisher publisher) {
        this.updater = updater;
        this.remover = remover;
        this.quarantineRepository = quarantineRepository;
        this.publisher = publisher;
    }

    public static QuarantinedEnvelopes of(InboxUpdater updater, InboxEnvelopeRemover remover,
                                          QuarantineRepository quarantineRepository, EventPublisher publisher) {
        return new QuarantinedEnvelopes(updater, remover, quarantineRepository, publisher);
    }

    @Override
    public Collection<IdempotencyKey> republish(Collection<IdempotencyKey> idempotencyKeys) {
        List<QuarantinedRecord> pending = pendingInTheInbox(idempotencyKeys);
        if (pending.isEmpty()) return List.of();
        updater.update(keysOf(pending), EnvelopeStatus.CAPTURED);
        Instant now = Instant.now();
        pending.forEach(quarantinedRecord -> {
            Envelope envelope = quarantinedRecord.envelope();
            updater.update(capturedWithoutRetries(envelope, now));
            quarantineRepository.save(quarantinedRecord.resolve(QuarantineResolution.republished(now)));
            publisher.publish(storedEventOf(envelope, envelope.idempotencyKey()));
            publisher.publish(republishedEventOf(envelope, envelope.idempotencyKey()));
        });
        LOG.info("Republished {} envelopes from quarantine", pending.size());
        return keysOf(pending);
    }

    @Override
    public Optional<IdempotencyKey> republish(IdempotencyKey idempotencyKey, byte[] correctedPayload) {
        Optional<QuarantinedRecord> pending = pendingInTheInbox(List.of(idempotencyKey)).stream().findFirst();
        if (pending.isEmpty()) return Optional.empty();
        QuarantinedRecord quarantinedRecord = pending.get();
        Envelope original = quarantinedRecord.envelope();
        IdempotencyKey correctedKey = IdempotencyKey.of(sourceOf(original), correctedPayload);
        if (correctedKey.equals(idempotencyKey)) {
            return republish(List.of(idempotencyKey)).stream().findFirst();
        }
        Instant now = Instant.now();
        updater.update(new Envelope(correctedKey, correctedMetadataOf(original), original.mediaType(), correctedPayload,
                EnvelopeStatus.CAPTURED, 0, now, now));
        remover.remove(List.of(idempotencyKey));
        quarantineRepository.save(quarantinedRecord.resolve(QuarantineResolution.corrected(now, correctedKey)));
        publisher.publish(storedEventOf(original, correctedKey));
        publisher.publish(republishedEventOf(original, correctedKey));
        LOG.info("Republished {} from quarantine as {} with a corrected payload", idempotencyKey.value(), correctedKey.value());
        return Optional.of(correctedKey);
    }

    @Override
    public Collection<IdempotencyKey> purge(Collection<IdempotencyKey> idempotencyKeys) {
        List<QuarantinedRecord> pending = pendingInTheInbox(idempotencyKeys);
        if (pending.isEmpty()) return List.of();
        List<IdempotencyKey> keys = keysOf(pending);
        remover.remove(keys);
        Instant now = Instant.now();
        pending.forEach(quarantinedRecord -> {
            quarantineRepository.save(quarantinedRecord.resolve(QuarantineResolution.purged(now)));
            publisher.publish(EnvelopePurged.builder()
                    .correlationId(quarantinedRecord.envelope().metadata().get(EventMetadataKey.CORRELATION_ID))
                    .idempotencyKey(quarantinedRecord.idempotencyKey())
                    .build());
        });
        LOG.warn("Purged {} envelopes from quarantine", keys.size());
        return keys;
    }

    private List<QuarantinedRecord> pendingInTheInbox(Collection<IdempotencyKey> idempotencyKeys) {
        List<QuarantinedRecord> selected = new ArrayList<>();
        for (IdempotencyKey idempotencyKey : new LinkedHashSet<>(idempotencyKeys)) {
            Optional<QuarantinedRecord> found = quarantineRepository.find(idempotencyKey);
            if (found.isEmpty()) {
                LOG.warn("Skipped {}: it is not in quarantine", idempotencyKey.value());
            } else if (found.get().status() != QuarantineStatus.PENDING) {
                LOG.warn("Skipped {}: its quarantine is already {}", idempotencyKey.value(), found.get().status());
            } else if (!(found.get().location() instanceof QuarantineLocation.Inbox)) {
                LOG.warn("Skipped {}: it stayed in bronze, and quarantine records from bronze cannot be resolved yet",
                        idempotencyKey.value());
            } else {
                selected.add(found.get());
            }
        }
        return selected;
    }

    private static List<IdempotencyKey> keysOf(Collection<QuarantinedRecord> records) {
        return records.stream().map(QuarantinedRecord::idempotencyKey).toList();
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

    private static Envelope capturedWithoutRetries(Envelope envelope, Instant updatedAt) {
        return new Envelope(envelope.idempotencyKey(), envelope.metadata(), envelope.mediaType(), envelope.payload(),
                EnvelopeStatus.CAPTURED, 0, envelope.capturedAt(), updatedAt);
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
