package software.spool.feeder.api.builder;

import software.spool.core.adapter.watchdog.HttpWatchdogClient;
import software.spool.core.model.watchdog.ModuleIdentity;
import software.spool.core.port.watchdog.ModuleHeartBeat;
import software.spool.core.utils.polling.PollingHeartbeat;

import java.util.Objects;

public class FeederBuilderFactory {
    public static PollingFeederBuilder polling() {
        return new Configuration().polling();
    }

    public static ReactiveFeederBuilder reactive() {
        return new Configuration().reactive();
    }

    public static Configuration watchdog(String url, String moduleId) {
        return new Configuration(url, moduleId);
    }

    public static final class Configuration {
        private final String watchdogUrl;
        private final String moduleId;

        private Configuration(String watchdogUrl, String moduleId) {
            this.watchdogUrl = watchdogUrl;
            this.moduleId = moduleId;
        }

        private Configuration() {
            this(null, "feeder");
        }

        public PollingFeederBuilder polling() {
            return new PollingFeederBuilder(buildHeartbeat(watchdogUrl, moduleId));
        }

        public ReactiveFeederBuilder reactive() {
            return new ReactiveFeederBuilder(buildHeartbeat(watchdogUrl, moduleId));
        }
    }

    private static ModuleHeartBeat buildHeartbeat(String watchdogUrl, String moduleId) {
        return Objects.isNull(watchdogUrl) ?
                ModuleHeartBeat.NOOP : new PollingHeartbeat(
                new HttpWatchdogClient(watchdogUrl),
                ModuleIdentity.of(moduleId)
        );
    }
}
