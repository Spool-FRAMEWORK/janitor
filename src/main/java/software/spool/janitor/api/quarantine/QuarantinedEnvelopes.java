package software.spool.janitor.api.quarantine;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.model.quarantine.Resolver;
import software.spool.core.model.quarantine.QuarantinePageRequest;
import software.spool.core.model.quarantine.QuarantinePage;
import software.spool.core.exception.QuarantineConflictException;
import software.spool.core.exception.DataLakeWriteException;
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
import software.spool.core.model.vo.PartitionKey;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.model.vo.PartitionedEnvelope;
import software.spool.core.port.datalake.DataLakeWriter;
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
 * The inbox is never touched for an envelope that stayed in bronze. A bypass sends it back the same way, with the
 * {@code VALIDATION_BYPASS} metadata.
 * </p>
 *
 * <p>
 * Every resolution keeps who made it. A record is claimed first, by saving it resolved only if it is still pending,
 * and then its envelope is sent back: of two resolutions of the same record at once, only one sends it back. If
 * sending it back fails, the record is pending again.
 * </p>
 */
public final class QuarantinedEnvelopes implements QuarantinedEnvelopeRepublisher, QuarantinedEnvelopeBypasser,
        QuarantinedEnvelopePurger, QuarantinedEnvelopeFinder {
    private static final Logger LOG = LoggerFactory.getLogger(QuarantinedEnvelopes.class);

    private final InboxUpdater updater;
    private final InboxEnvelopeRemover remover;
    private final Optional<DataLakeWriter> dataLakeWriter;
    private final QuarantineRepository quarantineRepository;
    private final EventPublisher publisher;

    private QuarantinedEnvelopes(InboxUpdater updater, InboxEnvelopeRemover remover, Optional<DataLakeWriter> dataLakeWriter,
                                 QuarantineRepository quarantineRepository, EventPublisher publisher) {
        this.updater = updater;
        this.remover = remover;
        this.dataLakeWriter = dataLakeWriter;
        this.quarantineRepository = quarantineRepository;
        this.publisher = publisher;
    }

    /**
     * Resolves envelopes from the inbox and from bronze.
     */
    public static QuarantinedEnvelopes of(InboxUpdater updater, InboxEnvelopeRemover remover, DataLakeWriter dataLakeWriter,
                                          QuarantineRepository quarantineRepository, EventPublisher publisher) {
        return new QuarantinedEnvelopes(updater, remover, Optional.of(dataLakeWriter), quarantineRepository, publisher);
    }

    /**
     * Resolves envelopes from the inbox only; records of envelopes that stayed in bronze are skipped.
     */
    public static QuarantinedEnvelopes of(InboxUpdater updater, InboxEnvelopeRemover remover,
                                          QuarantineRepository quarantineRepository, EventPublisher publisher) {
        return new QuarantinedEnvelopes(updater, remover, Optional.empty(), quarantineRepository, publisher);
    }

    @Override
    public Optional<QuarantinedRecord> find(IdempotencyKey idempotencyKey) {
        return quarantineRepository.find(idempotencyKey);
    }

    @Override
    public QuarantinePage list(QuarantinePageRequest request) {
        return quarantineRepository.findByStatus(request);
    }

    @Override
    public Collection<IdempotencyKey> republish(Collection<IdempotencyKey> idempotencyKeys, Resolver resolver) {
        List<IdempotencyKey> republished = new ArrayList<>();
        for (QuarantinedRecord quarantinedRecord : resolvable(idempotencyKeys)) {
            Instant now = Instant.now();
            Envelope envelope = quarantinedRecord.envelope();
            boolean claimed = claimAndThen(quarantinedRecord, QuarantineResolution.republished(now, resolver), () -> {
                if (stayedInTheInbox(quarantinedRecord)) updater.update(List.of(envelope.idempotencyKey()), EnvelopeStatus.CAPTURED);
                sendBack(quarantinedRecord, envelope, now);
            });
            if (!claimed) continue;
            publisher.publish(republishedEventOf(envelope, envelope.idempotencyKey(), EnvelopeRepublished.Resolution.UNCHANGED));
            republished.add(envelope.idempotencyKey());
        }
        if (!republished.isEmpty()) LOG.info("Republished {} envelopes from quarantine", republished.size());
        return republished;
    }

    @Override
    public Optional<IdempotencyKey> republish(IdempotencyKey idempotencyKey, byte[] correctedPayload, Resolver resolver) {
        Optional<QuarantinedRecord> pending = resolvable(List.of(idempotencyKey)).stream().findFirst();
        if (pending.isEmpty()) return Optional.empty();
        QuarantinedRecord quarantinedRecord = pending.get();
        Envelope original = quarantinedRecord.envelope();
        IdempotencyKey correctedKey = IdempotencyKey.of(sourceOf(original), correctedPayload);
        if (correctedKey.equals(idempotencyKey)) {
            return republish(List.of(idempotencyKey), resolver).stream().findFirst();
        }
        Instant now = Instant.now();
        Envelope corrected = new Envelope(correctedKey, correctedMetadataOf(original), original.mediaType(), correctedPayload,
                original.status(), 0, now, now);
        boolean claimed = claimAndThen(quarantinedRecord, QuarantineResolution.corrected(now, correctedKey, resolver), () -> {
            sendBack(quarantinedRecord, corrected, now);
            if (stayedInTheInbox(quarantinedRecord)) remover.remove(List.of(idempotencyKey));
        });
        if (!claimed) return Optional.empty();
        publisher.publish(republishedEventOf(original, correctedKey, EnvelopeRepublished.Resolution.CORRECTED));
        LOG.info("Republished {} from quarantine as {} with a corrected payload", idempotencyKey.value(), correctedKey.value());
        return Optional.of(correctedKey);
    }

    @Override
    public Optional<IdempotencyKey> bypass(IdempotencyKey idempotencyKey, String reason, Resolver resolver) {
        Instant now = Instant.now();
        QuarantineResolution bypassed = QuarantineResolution.bypassed(now, reason, resolver);
        Optional<QuarantinedRecord> pending = resolvable(List.of(idempotencyKey)).stream().findFirst();
        if (pending.isEmpty()) return Optional.empty();
        QuarantinedRecord quarantinedRecord = pending.get();
        Envelope original = quarantinedRecord.envelope();
        Envelope marked = new Envelope(original.idempotencyKey(), bypassedMetadataOf(original, reason), original.mediaType(),
                original.payload(), original.status(), original.retries(), original.capturedAt(), now);
        if (!claimAndThen(quarantinedRecord, bypassed, () -> sendBack(quarantinedRecord, marked, now))) return Optional.empty();
        publisher.publish(republishedEventOf(original, idempotencyKey, EnvelopeRepublished.Resolution.BYPASSED));
        LOG.warn("Republished {} from quarantine with a validation bypass: {}", idempotencyKey.value(), reason);
        return Optional.of(idempotencyKey);
    }

    @Override
    public Collection<IdempotencyKey> purge(Collection<IdempotencyKey> idempotencyKeys, Resolver resolver) {
        List<IdempotencyKey> purged = new ArrayList<>();
        for (QuarantinedRecord quarantinedRecord : resolvable(idempotencyKeys)) {
            boolean claimed = claimAndThen(quarantinedRecord, QuarantineResolution.purged(Instant.now(), resolver), () -> {
                if (stayedInTheInbox(quarantinedRecord)) remover.remove(List.of(quarantinedRecord.idempotencyKey()));
            });
            if (!claimed) continue;
            publisher.publish(EnvelopePurged.builder()
                    .correlationId(quarantinedRecord.envelope().metadata().get(EventMetadataKey.CORRELATION_ID))
                    .idempotencyKey(quarantinedRecord.idempotencyKey())
                    .build());
            purged.add(quarantinedRecord.idempotencyKey());
        }
        if (!purged.isEmpty()) LOG.warn("Purged {} envelopes from quarantine", purged.size());
        return purged;
    }

    private boolean claimAndThen(QuarantinedRecord pending, QuarantineResolution resolution, Runnable sendBack) {
        try {
            quarantineRepository.resolve(pending.resolve(resolution));
        } catch (QuarantineConflictException e) {
            LOG.warn("Skipped {}: {}", pending.idempotencyKey().value(), e.getMessage());
            return false;
        }
        try {
            sendBack.run();
            return true;
        } catch (RuntimeException e) {
            quarantineRepository.save(pending);
            throw e;
        }
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
                writeBackToTheDataLake(bronze.partition(), envelope);
                publisher.publish(EnvelopePersisted.builder()
                        .correlationId(envelope.metadata().get(EventMetadataKey.CORRELATION_ID))
                        .idempotencyKey(envelope.idempotencyKey())
                        .partitionKey(bronze.partition())
                        .build());
            }
        }
    }

    private void writeBackToTheDataLake(PartitionKey partition, Envelope envelope) {
        boolean written = dataLakeWriter.orElseThrow().write(List.of(new PartitionedEnvelope(partition, envelope)))
                .anyMatch(envelope.idempotencyKey()::equals);
        if (!written) {
            throw new DataLakeWriteException("envelope " + envelope.idempotencyKey().value()
                    + " could not be written back to partition " + partition.value());
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
            } else if (!stayedInTheInbox(found.get()) && dataLakeWriter.isEmpty()) {
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

    private static EventMetadata bypassedMetadataOf(Envelope original, String reason) {
        return new EventMetadata(new HashMap<>(original.metadata().metadata()))
                .set(EventMetadataKey.VALIDATION_BYPASS, reason);
    }

    private static EnvelopeRepublished republishedEventOf(Envelope original, IdempotencyKey republishedKey,
                                                          EnvelopeRepublished.Resolution resolution) {
        return EnvelopeRepublished.builder()
                .correlationId(original.metadata().get(EventMetadataKey.CORRELATION_ID))
                .idempotencyKey(original.idempotencyKey())
                .republishedIdempotencyKey(republishedKey)
                .resolution(resolution)
                .build();
    }
}
