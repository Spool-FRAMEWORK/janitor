package software.spool.publisher.api.utils;

import software.spool.core.model.Event;

import java.util.HashMap;
import java.util.Map;

public class ChannelRouter {
    private final Map<Class<? extends Event>, String> routes;

    public ChannelRouter() {
        routes = new HashMap<>();
    }

    public <T extends Event> ChannelRouter route(Class<T> eventType, String channel) {
        routes.put(eventType, channel);
        return this;
    }

    public String resolve(Event event) {
        return routes.getOrDefault(event.getClass(), "spool");
    }
}
