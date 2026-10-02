package com.example.pim.bootstrap;

import com.example.pim.application.port.in.PurgeDiscardedDraftsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "pim.retention.enabled", havingValue = "true", matchIfMissing = true)
class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final PurgeDiscardedDraftsUseCase purge;
    private final Duration retention;

    RetentionJob(PurgeDiscardedDraftsUseCase purge, @Value("${pim.retention.discarded-drafts:P30D}") Duration retention) {
        this.purge = purge;
        this.retention = retention;
    }

    @Scheduled(cron = "${pim.retention.cron:0 30 3 * * *}")
    void purgeDiscardedDrafts() {
        int purged = purge.purgeOlderThan(retention);
        log.info("Purged {} discarded drafts older than {}", purged, retention);
    }
}
