package com.example.pim.application.port.out;

import java.time.Instant;

public interface VersionRetentionRepository {

    /** Deletes discarded drafts created before the cut-off. Returns how many were removed. */
    int purgeDiscardedCreatedBefore(Instant cutoff);
}
