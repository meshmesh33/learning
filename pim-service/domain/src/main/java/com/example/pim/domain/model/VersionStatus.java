package com.example.pim.domain.model;

public enum VersionStatus {
    /** Mutable working copy. At most one per product set. Never visible to customers. */
    DRAFT,
    /** Frozen content that may be assigned to countries. */
    APPROVED,
    /** Abandoned draft, removed later by retention. */
    DISCARDED
}
