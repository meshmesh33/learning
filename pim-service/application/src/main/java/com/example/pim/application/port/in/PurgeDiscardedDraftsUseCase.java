package com.example.pim.application.port.in;

import java.time.Duration;

public interface PurgeDiscardedDraftsUseCase {

    /** Removes discarded drafts (and content only they used) older than the retention period. */
    int purgeOlderThan(Duration retention);
}
