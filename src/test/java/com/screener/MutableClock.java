package com.screener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class MutableClock extends Clock {
    private Instant now;

    public MutableClock(Instant now) {
        this.now = now;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    public void set(Instant i) {
        now = i;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock self = this;
        return new Clock() {
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId z) { return self.withZone(z); }
            @Override public Instant instant() { return self.instant(); }
        };
    }

    @Override
    public Instant instant() {
        return now;
    }
}
