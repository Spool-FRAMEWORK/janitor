package software.spool.publisher.api.builder;

public class FeederBuilderFactory {
    public static PollingFeederBuilder polling() {
        return PollingFeederBuilder.create();
    }

    public static ReactiveFeederBuilder reactive() {
        return ReactiveFeederBuilder.create();
    }
}
