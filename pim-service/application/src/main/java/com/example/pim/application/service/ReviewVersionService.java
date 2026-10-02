package com.example.pim.application.service;

import com.example.pim.application.port.in.ReviewVersionUseCase;
import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.domain.model.ProductSetVersion;

import java.time.Clock;

public class ReviewVersionService implements ReviewVersionUseCase {

    private final VersionLookup lookup;
    private final Clock clock;

    public ReviewVersionService(ProductSetVersionRepository versions, Clock clock) {
        this.lookup = new VersionLookup(versions);
        this.clock = clock;
    }

    @Override
    public ProductSetVersion approve(ApproveVersionCommand command) {
        return lookup.change(command.versionId(), command.expectedLockVersion(),
                draft -> draft.approve(command.approver(), clock.instant()));
    }

    @Override
    public void discard(DiscardDraftCommand command) {
        lookup.change(command.versionId(), command.expectedLockVersion(), ProductSetVersion::discard);
    }
}
