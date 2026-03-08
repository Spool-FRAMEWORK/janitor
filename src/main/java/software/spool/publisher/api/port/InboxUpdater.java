package software.spool.publisher.api.port;

import software.spool.core.exception.InboxUpdateException;
import software.spool.core.model.IdempotencyKey;
import software.spool.core.model.InboxItemStatus;
import software.spool.publisher.api.InboxItem;

public interface InboxUpdater {
    InboxItem update(IdempotencyKey idempotencyKey, InboxItemStatus status) throws InboxUpdateException;
}
