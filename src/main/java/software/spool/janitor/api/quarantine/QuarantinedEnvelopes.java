package software.spool.janitor.api.quarantine;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.event.EnvelopePersisted;
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
import software.spool.core.port.datalake.BronzeRepository;
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
 * after the inbox has removed it. Resolving a record marks how it ended instead of deleting it, and sends the
 * envelope back to where it stayed: an envelope from the inbox returns to the inbox as {@code CAPTURED}, and one
 * from bronze is written again to its bronze partition, followed by {@link EnvelopePersisted} so it is mounted again.
 * The inbox is never touched for an envelope that stayed in bronze.
 * </p>
 */
public final class QuarantinedEnvelopes implements QuarantinedEnvelopeRepublisher, QuarantinedEnvelopePurger {
    private static final Logger LOG = LoggerFactory.getLogger(QuarantinedEnvelopes.class);

    private final InboxUpdater updater;
    private final InboxEnvelopeRemover remover;
    private final Optional<BronzeRepository> bronzeRepository;
    private final QuarantineRepository quarantineRepository;
    private final EventPublisher publisher;

    private QuarantinedEnvelopes(InboxUpdater updater, InboxEnvelopeRemover remover, Optional<BronzeRepository> bronzeRepository,
                                 QuarantineRepository quarantineRepository, EventPublisher publisher) {
        this.updater = updater;
        this.remover = remover;
        this.bronzeRepository = bronzeRepository;
        this.quarantineRepository = quarantineRepository;
        this.publisher = publisher;
    }

    /**
     * Resolves envelopes from the inbox and from bronze.
     */
    public static QuarantinedEnvelopes of(InboxUpdater updater, InboxEnvelopeRemover remover, BronzeRepository bronzeRepository,
                                          QuarantineRepository quarantineRepository, EventPublisher publisher) {
        return new QuarantinedEnvelopes(updater, remover, Optional.of(bronzeRepository), quarantineRepository, publisher);
    }

    /**
     * Resolves envelopes from the inbox only; records of envelopes that stayed in bronze are skipped.
     */
    public static QuarantinedEnvelopes of(InboxUpdater updater, InboxEnvelopeRemover remover,
                                          QuarantineRepository quarantineRepository, EventPublisher publisher) {
        return new QuarantinedEnvelopes(updater, remover, Optional.empty(), quarantineRepository, publisher);
    }

    @Override
    public Collection<IdempotencyKey> republish(Collection<IdempotencyKey> idempotencyKeys) {
        List<QuarantinedRecord> pending = resolvable(idempotencyKeys);
        if (pending.isEmpty()) return List.of();
        Instant now = Instant.now();
        List<QuarantinedRecord> fromTheInbox = pending.stream().filter(QuarantinedEnvelopes::stayedInTheInbox).toList();
        if (!fromTheInbox.isEmpty()) updater.update(keysOf(fromTheInbox), EnvelopeStatus.CAPTURED);
        pending.forEach(quarantinedRecord -> {
            Envelope envelope = quarantinedRecord.envelope();
            sendBack(quarantinedRecord, envelope, now);
            quarantineRepository.save(quarantinedRecord.resolve(QuarantineResolution.republished(now)));
            publisher.publish(republishedEventOf(envelope, envelope.idempotencyKey()));
        });
        LOG.info("Republished {} envelopes from quarantine", pending.size());
        return keysOf(pending);
    }

    @Override
    public Optional<IdempotencyKey> republish(IdempotencyKey idempotencyKey, byte[] correctedPayload) {
        Optional<QuarantinedRecord> pending = resolvable(List.of(idempotencyKey)).stream().findFirst();
        if (pending.isEmpty()) return Optional.empty();
        QuarantinedRecord quarantinedRecord = pending.get();
        Envelope original = quarantinedRecord.envelope();
        IdempotencyKey correctedKey = IdempotencyKey.of(sourceOf(original), correctedPayload);
        if (correctedKey.equals(idempotencyKey)) {
            return republish(List.of(idempotencyKey)).stream().findFirst();
        }
        Instant now = Instant.now();
        Envelope corrected = new Envelope(correctedKey, correctedMetadataOf(original), original.mediaType(), correctedPayload,
                original.status(), 0, now, now);
        sendBack(quarantinedRecord, corrected, now);
        if (stayedInTheInbox(quarantinedRecord)) remover.remove(List.of(idempotencyKey));
        quarantineRepository.save(quarantinedRecord.resolve(QuarantineResolution.corrected(now, correctedKey)));
        publisher.publish(republishedEventOf(original, correctedKey));
        LOG.info("Republished {} from quarantine as {} with a corrected payload", idempotencyKey.value(), correctedKey.value());
        return Optional.of(correctedKey);
    }

    @Override
    public Collection<IdempotencyKey> purge(Collection<IdempotencyKey> idempotencyKeys) {
        List<QuarantinedRecord> pending = resolvable(idempotencyKeys);
        if (pending.isEmpty()) return List.of();
        List<IdempotencyKey> fromTheInbox = keysOf(pending.stream().filter(QuarantinedEnvelopes::stayedInTheInbox).toList());
        if (!fromTheInbox.isEmpty()) remover.remove(fromTheInbox);
        Instant now = Instant.now();
        pending.forEach(quarantinedRecord -> {
            quarantineRepository.save(quarantinedRecord.resolve(QuarantineResolution.purged(now)));
            publisher.publish(EnvelopePurged.builder()
                    .correlationId(quarantinedRecord.envelope().metadata().get(EventMetadataKey.CORRELATION_ID))
                    .idempotencyKey(quarantinedRecord.idempotencyKey())
                    .build());
        });
        LOG.warn("Purged {} envelopes from quarantine", pending.size());
        return keysOf(pending);
    }

    private void sendBack(QuarantinedRecord quarantinedRecord, Envelope envelope, Instant now) {
        switch (quarantinedRecord.location()) {
            case QuarantineLocation.Inbox inbox -> {
                updater.update(capturedWithoutRetries(envelope, now));
                publisher.publish(EnvelopeStored.builder()
                        .correlationId(envelope.metadata().get(EventMetadataKey.CORRELATION_ID))
                        .idempotencyKey(envelope.idempotencyKey())
                        .build());
            }
            case QuarantineLocation.Bronze bronze -> {
                bronzeRepository.orElseThrow().save(bronze.partition(), envelope);
                publisher.publish(EnvelopePersisted.builder()
                        .correlationId(envelope.metadata().get(EventMetadataKey.CORRELATION_ID))
                        .idempotencyKey(envelope.idempotencyKey())
                        .partitionKey(bronze.partition())
                        .build());
            }
        }
    }

    private List<QuarantinedRecord> resolvable(Collection<IdempotencyKey> idempotencyKeys) {
        List<QuarantinedRecord> selected = new ArrayList<>();
        for (IdempotencyKey idempotencyKey : new LinkedHashSet<>(idempotencyKeys)) {
            Optional<QuarantinedRecord> found = quarantineRepository.find(idempotencyKey);
            if (found.isEmpty()) {
                LOG.warn("Skipped {}: it is not in quarantine", idempotencyKey.value());
            } else if (found.get().status() != QuarantineStatus.PENDING) {
                LOG.warn("Skipped {}: its quarantine is already {}", idempotencyKey.value(), found.get().status());
            } else if (!stayedInTheInbox(found.get()) && bronzeRepository.isEmpty()) {
                LOG.warn("Skipped {}: it stayed in bronze, and this janitor has no data lake to write it back to",
                        idempotencyKey.value());
            } else {
                selected.add(found.get());
            }
        }
        return selected;
    }

    private static boolean stayedInTheInbox(QuarantinedRecord quarantinedRecord) {
        return quarantinedRecord.location() instanceof QuarantineLocation.Inbox;
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

    private static EnvelopeRepublished republishedEventOf(Envelope original, IdempotencyKey republishedKey) {
        return EnvelopeRepublished.builder()
                .correlationId(original.metadata().get(EventMetadataKey.CORRELATION_ID))
                .idempotencyKey(original.idempotencyKey())
                .republishedIdempotencyKey(republishedKey)
                .build();
    }
}
