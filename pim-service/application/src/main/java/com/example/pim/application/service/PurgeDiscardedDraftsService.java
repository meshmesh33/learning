package com.example.pim.application.service;

import com.example.pim.application.port.in.PurgeDiscardedDraftsUseCase;
import com.example.pim.application.port.out.VersionRetentionRepository;

import java.time.Clock;
import java.time.Duration;

public class PurgeDiscardedDraftsService implements PurgeDiscardedDraftsUseCase {

    private final VersionRetentionRepository retention;
    private final Clock clock;

    public PurgeDiscardedDraftsService(VersionRetentionRepository retention, Clock clock) {
        this.retention = retention;
        this.clock = clock;
    }

    @Override
    public int purgeOlderThan(Duration retentionPeriod) {
        return retention.purgeDiscardedCreatedBefore(clock.instant().minus(retentionPeriod));
    }
}
