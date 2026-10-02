package com.example.pim.application.port.in;

import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.UserId;
import com.example.pim.domain.model.VersionId;

/** Ends a draft's life: approve it (freeze) or discard it. Neither publishes anything. */
public interface ReviewVersionUseCase {

    ProductSetVersion approve(ApproveVersionCommand command);

    void discard(DiscardDraftCommand command);

    record ApproveVersionCommand(VersionId versionId, int expectedLockVersion, UserId approver) {
    }

    record DiscardDraftCommand(VersionId versionId, int expectedLockVersion) {
    }
}
