package software.spool.janitor.internal.adapter.input.http;

import software.spool.core.model.quarantine.QuarantineEntry;
import software.spool.core.model.quarantine.QuarantineLocation;
import software.spool.core.model.quarantine.QuarantineResolution;
import software.spool.core.model.quarantine.QuarantinedRecord;
import software.spool.core.model.quarantine.Resolver;
import software.spool.core.model.vo.Envelope;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A quarantine record as the API shows it: its envelope and every stay in quarantine, with who resolved it. A JSON
 * or text payload is shown as text; any other in Base64, as {@code payloadEncoding} says.
 */
record QuarantinedRecordPayload(String key, String status, LocationPayload location, EnvelopePayload envelope,
                                List<StayPayload> history) {
    private static final String TEXT = "utf-8";
    private static final String BINARY = "base64";
    private static final String TEXT_TYPES = "text/";
    private static final String JSON_SUBTYPE = "json";

    static QuarantinedRecordPayload of(QuarantinedRecord quarantinedRecord) {
        return new QuarantinedRecordPayload(quarantinedRecord.idempotencyKey().value(), quarantinedRecord.status().name(),
                LocationPayload.of(quarantinedRecord.location()), EnvelopePayload.of(quarantinedRecord.envelope()),
                quarantinedRecord.history().stream().map(StayPayload::of).toList());
    }

    record LocationPayload(String place, Optional<String> partition) {
        static LocationPayload of(QuarantineLocation location) {
            return switch (location) {
                case QuarantineLocation.Inbox inbox -> new LocationPayload("INBOX", Optional.empty());
                case QuarantineLocation.Bronze bronze -> new LocationPayload("BRONZE", Optional.of(bronze.partition().value()));
            };
        }
    }

    record EnvelopePayload(Map<String, String> metadata, String mediaType, String payload, String payloadEncoding) {
        static EnvelopePayload of(Envelope envelope) {
            Map<String, String> metadata = new LinkedHashMap<>();
            envelope.metadata().metadata().forEach((key, value) -> metadata.put(key.name(), value));
            String mediaType = envelope.mediaType().value();
            boolean readable = isText(mediaType);
            return new EnvelopePayload(metadata, mediaType,
                    readable ? new String(envelope.payload(), StandardCharsets.UTF_8) : Base64.getEncoder().encodeToString(envelope.payload()),
                    readable ? TEXT : BINARY);
        }

        private static boolean isText(String mediaType) {
            String type = mediaType.toLowerCase(Locale.ROOT);
            return type.startsWith(TEXT_TYPES) || type.contains(JSON_SUBTYPE);
        }
    }

    record StayPayload(String reason, List<String> violations, Instant quarantinedAt, ResolutionPayload resolution) {
        static StayPayload of(QuarantineEntry entry) {
            return new StayPayload(entry.reason().name(), entry.violations(), entry.quarantinedAt(),
                    ResolutionPayload.of(entry.resolution()));
        }
    }

    record ResolutionPayload(String status, Optional<Instant> at, Optional<ResolverPayload> by, Optional<String> reason,
                             Optional<String> amendedTo) {
        static ResolutionPayload of(QuarantineResolution resolution) {
            return switch (resolution) {
                case QuarantineResolution.Pending pending ->
                        new ResolutionPayload(pending.status().name(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
                case QuarantineResolution.Republished republished ->
                        new ResolutionPayload(republished.status().name(), Optional.of(republished.at()), ResolverPayload.of(republished.by()),
                                Optional.empty(), Optional.empty());
                case QuarantineResolution.Corrected corrected ->
                        new ResolutionPayload(corrected.status().name(), Optional.of(corrected.at()), ResolverPayload.of(corrected.by()),
                                Optional.empty(), Optional.of(corrected.correctedTo().value()));
                case QuarantineResolution.Bypassed bypassed ->
                        new ResolutionPayload(bypassed.status().name(), Optional.of(bypassed.at()), ResolverPayload.of(bypassed.by()),
                                Optional.of(bypassed.reason()), Optional.empty());
                case QuarantineResolution.Purged purged ->
                        new ResolutionPayload(purged.status().name(), Optional.of(purged.at()), ResolverPayload.of(purged.by()),
                                Optional.empty(), Optional.empty());
            };
        }
    }

    record ResolverPayload(String subject, String provider) {
        static Optional<ResolverPayload> of(Resolver resolver) {
            return switch (resolver) {
                case Resolver.Identified identified -> Optional.of(new ResolverPayload(identified.subject(), identified.provider()));
                case Resolver.Unknown unknown -> Optional.empty();
            };
        }
    }
}
