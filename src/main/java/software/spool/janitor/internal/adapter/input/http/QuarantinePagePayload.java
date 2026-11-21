package software.spool.janitor.internal.adapter.input.http;

import software.spool.core.model.quarantine.QuarantinePage;
import software.spool.core.model.vo.IdempotencyKey;

import java.util.List;
import java.util.Optional;

/**
 * One page of the quarantine, and the cursor that asks for the next one when there is a next one.
 */
record QuarantinePagePayload(List<QuarantinedRecordPayload> records, Optional<String> next) {
    static QuarantinePagePayload of(QuarantinePage page) {
        return new QuarantinePagePayload(page.records().stream().map(QuarantinedRecordPayload::of).toList(),
                page.next().map(IdempotencyKey::value));
    }
}
