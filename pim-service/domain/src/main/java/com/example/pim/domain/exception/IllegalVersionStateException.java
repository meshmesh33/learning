package com.example.pim.domain.exception;

import com.example.pim.domain.model.VersionId;
import com.example.pim.domain.model.VersionStatus;

/** The operation is not allowed in the version's current lifecycle state. */
public class IllegalVersionStateException extends DomainException {

    public IllegalVersionStateException(String message) {
        super(message);
    }

    public static IllegalVersionStateException notEditable(VersionId id, VersionStatus status) {
        return new IllegalVersionStateException("Version %s is %s and cannot be changed".formatted(id, status));
    }

    public static IllegalVersionStateException notPublishable(VersionId id, VersionStatus status) {
        return new IllegalVersionStateException(
                "Version %s is %s; only APPROVED versions can be published".formatted(id, status));
    }

    public static IllegalVersionStateException cannotBranchFrom(VersionId id, VersionStatus status) {
        return new IllegalVersionStateException(
                "A draft can only be branched from an APPROVED version; %s is %s".formatted(id, status));
    }
}
