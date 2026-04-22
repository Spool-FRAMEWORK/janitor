package software.spool.feeder.internal.control;

import software.spool.core.model.*;
import software.spool.core.model.event.InboxItemStored;
import software.spool.core.model.event.ItemPublished;
import software.spool.core.model.vo.InboxItem;
import software.spool.core.port.bus.EventBusEmitter;
import software.spool.core.port.bus.Handler;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.utils.routing.ErrorRouter;

import java.util.Objects;

/**
 * Handler that processes {@link InboxItemStored} events by updating the
 * inbox item status through a publishing lifecycle.
 *
 * <p>
 * The processing flow for each event:
 * </p>
 * <ol>
 * <li>Update the inbox item status to {@code PUBLISHING}.</li>
 * <li>Emit an {@link ItemPublished} event with the item payload.</li>
 * <li>Update the inbox item status to {@code PUBLISHED}.</li>
 * </ol>
 *
 * <p>
 * If the inbox item no longer exists (update returns {@code null}), the
 * event is silently skipped.
 * </p>
 */
public class InboxItemStoredHandler implements Handler<InboxItemStored> {
    private final InboxUpdater updater;
    private final EventBusEmitter emitter;
    private final ErrorRouter errorRouter;

    /**
     * Creates a new handler with the given ports.
     *
     * @param updater the inbox updater for changing item statuses
     * @param emitter the event bus emitter for publishing events
     */
    public InboxItemStoredHandler(InboxUpdater updater, EventBusEmitter emitter, ErrorRouter errorRouter) {
        this.updater = Objects.requireNonNull(updater);
        this.emitter = Objects.requireNonNull(emitter);
        this.errorRouter = Objects.requireNonNull(errorRouter);
    }

    @Override
    public void handle(InboxItemStored object) {
        InboxItem item = updater.update(object.idempotencyKey(), InboxItemStatus.PUBLISHING);
        if (Objects.isNull(item)) return;
        try {
            emitter.emit(ItemPublished.builder()
                    .from(object)
                    .partitionKeySchema(item.partitionKeySchema())
                    .addMetadata(item.metadata())
                    .build());
            updater.update(object.idempotencyKey(), InboxItemStatus.PUBLISHED);
        } catch (Exception e) {
            updater.update(object.idempotencyKey(), InboxItemStatus.UNPUBLISHED);
            errorRouter.dispatch(e, object);
        }
    }
}
