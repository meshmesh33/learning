package com.example.pim.application.port.in;

import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.UserId;

public interface OpenDraftUseCase {

    /** Returns the open draft, or branches a new one from the latest approved version. */
    ProductSetVersion openDraft(OpenDraftCommand command);

    record OpenDraftCommand(ProductSetId productSetId, UserId requestedBy) {
    }
}
