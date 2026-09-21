package software.spool.janitor.api.quarantine;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.event.EnvelopeStored;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadataKey;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.inbox.InboxEnvelopeRemover;
import software.spool.core.port.inbox.InboxReader;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lets a person deal with the envelopes that ended up in quarantine.
 *
 * <p>An envelope can be requeued, to have the ingester process it again, or discarded, which deletes it and frees its
 * key so the same payload can be captured again. Both only act on envelopes that are in quarantine: a key that is
 * not, or that does not exist, is skipped and reported, and its envelope is not touched.</p>
 *
 * <p>The quarantine is read in full to know what is in it, which is fine for a place that should hold few envelopes.</p>
 */
public final class QuarantineReleaser {
    private static final Logger LOG = LoggerFactory.getLogger(QuarantineReleaser.class);

    private final InboxReader reader;
    private final InboxUpdater updater;
    private final InboxEnvelopeRemover remover;
    private final EventPublisher publisher;

    private QuarantineReleaser(InboxReader reader, InboxUpdater updater, InboxEnvelopeRemover remover, EventPublisher publisher) {
        this.reader = reader;
        this.updater = updater;
        this.remover = remover;
        this.publisher = publisher;
    }

    /**
     * Creates a releaser over the inbox of a node.
     *
     * @param reader    reads what is in quarantine
     * @param updater   moves the envelopes that are requeued
     * @param remover   deletes the envelopes that are discarded
     * @param publisher announces the envelopes that are requeued, so the ingester picks them up
     * @return the releaser
     */
    public static QuarantineReleaser using(InboxReader reader, InboxUpdater updater, InboxEnvelopeRemover remover, EventPublisher publisher) {
        return new QuarantineReleaser(reader, updater, remover, publisher);
    }

    /**
     * Lists the envelopes that are in quarantine.
     *
     * @return the envelopes
     */
    public Collection<Envelope> quarantined() {
        return reader.findByStatus(EnvelopeStatus.QUARANTINED);
    }

    /**
     * Returns envelopes from quarantine to the inbox, with their retries back to zero, and announces them so the
     * ingester processes them again.
     *
     * <p>If something fails half way, an envelope stays captured and the janitor treats it as stuck after its
     * threshold, as it does with any other.</p>
     *
     * @param keys the keys of the envelopes to requeue
     * @return which keys were requeued and which were skipped
     */
    public ReleaseResult requeue(Collection<IdempotencyKey> keys) {
        Selection selection = select(keys);
        if (selection.quarantined().isEmpty()) return new ReleaseResult(List.of(), selection.skipped());
        Collection<Envelope> moved = updater.update(selection.quarantined(), EnvelopeStatus.CAPTURED);
        moved.forEach(envelope -> {
            updater.update(withoutRetries(envelope));
            publisher.publish(storedEventOf(envelope));
        });
        LOG.info("Requeued {} envelopes from quarantine", moved.size());
        return new ReleaseResult(moved.stream().map(Envelope::idempotencyKey).toList(), selection.skipped());
    }

    /**
     * Deletes envelopes from quarantine, which frees their keys so the same payloads can be captured again. Deleting
     * is final.
     *
     * @param keys the keys of the envelopes to delete
     * @return which keys were deleted and which were skipped
     */
    public ReleaseResult discard(Collection<IdempotencyKey> keys) {
        Selection selection = select(keys);
        if (selection.quarantined().isEmpty()) return new ReleaseResult(List.of(), selection.skipped());
        remover.remove(selection.quarantined());
        LOG.warn("Discarded {} envelopes from quarantine", selection.quarantined().size());
        return new ReleaseResult(selection.quarantined(), selection.skipped());
    }

    private Selection select(Collection<IdempotencyKey> keys) {
        Set<IdempotencyKey> inQuarantine = quarantined().stream().map(Envelope::idempotencyKey).collect(Collectors.toSet());
        List<IdempotencyKey> quarantined = new ArrayList<>();
        List<IdempotencyKey> skipped = new ArrayList<>();
        for (IdempotencyKey key : new LinkedHashSet<>(keys)) {
            if (inQuarantine.contains(key)) {
                quarantined.add(key);
            } else {
                skipped.add(key);
                LOG.warn("Skipped {}: it is not in quarantine", key.value());
            }
        }
        return new Selection(quarantined, skipped);
    }

    private static Envelope withoutRetries(Envelope envelope) {
        return new Envelope(envelope.idempotencyKey(), envelope.metadata(), envelope.mediaType(), envelope.payload(),
                EnvelopeStatus.CAPTURED, 0, envelope.capturedAt(), envelope.updatedAt());
    }

    private static EnvelopeStored storedEventOf(Envelope envelope) {
        return EnvelopeStored.builder()
                .correlationId(envelope.metadata().get(EventMetadataKey.CORRELATION_ID))
                .idempotencyKey(envelope.idempotencyKey())
                .build();
    }

    private record Selection(List<IdempotencyKey> quarantined, List<IdempotencyKey> skipped) {
    }
}
