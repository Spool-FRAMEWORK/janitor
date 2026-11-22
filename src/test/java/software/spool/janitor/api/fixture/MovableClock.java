package software.spool.janitor.api.fixture;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that only moves when a test moves it. */
public final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-11-22T10:00:00Z");

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override public Instant instant() {
        return now;
    }
}
