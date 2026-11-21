package com.screener.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.screener.config.ScreenerProperties;

/** Weekday jobs, New York time. Off unless screener.schedule.enabled=true. */
@Component
public class DailyJobs {
    private static final Logger log = LoggerFactory.getLogger(DailyJobs.class);

    private final ScreenerProperties props;
    private final ScreenerService screener;
    private final SnapshotService snapshots;

    public DailyJobs(ScreenerProperties props, ScreenerService screener, SnapshotService snapshots) {
        this.props = props;
        this.screener = screener;
        this.snapshots = snapshots;
    }

    /** Screen at 8:00, then the bot snapshot. */
    @Scheduled(cron = "${screener.schedule.screen-cron:0 0 8 * * MON-FRI}", zone = "America/New_York")
    public void morningScreen() {
        if (!props.getSchedule().isEnabled()) return;
        try {
            screener.run(false);
            snapshots.build();
        } catch (Exception e) {
            log.warn("Morning screen failed: {}", e.getMessage());
        }
    }

    /** Refresh the snapshot just after the open so state reflects the first prints. */
    @Scheduled(cron = "${screener.schedule.snapshot-cron:0 35 9 * * MON-FRI}", zone = "America/New_York")
    public void openSnapshot() {
        if (!props.getSchedule().isEnabled()) return;
        try {
            snapshots.build();
        } catch (Exception e) {
            log.warn("Snapshot failed: {}", e.getMessage());
        }
    }
}
