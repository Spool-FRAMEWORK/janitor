package software.spool.publisher.internal.control;

import software.spool.core.control.Handler;
import software.spool.core.model.*;
import software.spool.core.port.*;

import java.util.Objects;

public class InboxItemStoredHandler implements Handler<InboxItemStored> {
    private final InboxUpdater updater;
    private final EventBusEmitter emitter;

    public InboxItemStoredHandler(InboxUpdater updater, EventBusEmitter emitter) {
        this.updater = updater;
        this.emitter = emitter;
    }

    @Override
    public void handle(InboxItemStored object) {
        InboxItem item = updater.update(object.idempotencyKey(), InboxItemStatus.PUBLISHING);
        if (Objects.isNull(item)) return;
        emitter.emit(ItemPublished.builder()
                .from(object)
                .payload(item.payload())
                .build());
        updater.update(object.idempotencyKey(), InboxItemStatus.PUBLISHED);
    }
}
