package software.spool.publisher.internal.decorator;

import software.spool.core.exception.InboxUpdateException;
import software.spool.core.exception.SpoolException;
import software.spool.core.model.IdempotencyKey;
import software.spool.core.model.InboxItemStatus;
import software.spool.publisher.api.InboxItem;
import software.spool.publisher.api.port.InboxUpdater;

public class SafeInboxUpdater implements InboxUpdater {
    private final InboxUpdater updater;

    public SafeInboxUpdater(InboxUpdater updater) {
        this.updater = updater;
    }

    @Override
    public InboxItem update(IdempotencyKey idempotencyKey, InboxItemStatus status) {
        try {
            return updater.update(idempotencyKey, status);
        } catch (SpoolException e) { throw e; }
        catch (Exception e) {
            throw new InboxUpdateException(idempotencyKey, e.getMessage(), e);
        }
    }

    public static SafeInboxUpdater of(InboxUpdater updater) {
        return new SafeInboxUpdater(updater);
    }
}
