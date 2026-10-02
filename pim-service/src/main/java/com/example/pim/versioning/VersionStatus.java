package com.example.pim.versioning;

public enum VersionStatus {
    /** Mutable working copy. At most one per product set. Never sent downstream. */
    DRAFT,
    /** Frozen. Can be assigned to countries and published. */
    APPROVED,
    /** Abandoned draft. Kept until retention cleans it up. */
    DISCARDED
}
