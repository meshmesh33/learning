package com.example.pim.application.service;

import com.example.pim.application.exception.ResourceNotFoundException;
import com.example.pim.application.port.in.OpenDraftUseCase;
import com.example.pim.application.port.out.ProductSetRepository;
import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.ProductSetVersion;

public class OpenDraftService implements OpenDraftUseCase {

    private final ProductSetRepository productSets;
    private final ProductSetVersionRepository versions;

    public OpenDraftService(ProductSetRepository productSets, ProductSetVersionRepository versions) {
        this.productSets = productSets;
        this.versions = versions;
    }

    @Override
    public ProductSetVersion openDraft(OpenDraftCommand command) {
        ProductSetId productSetId = command.productSetId();
        productSets.findAndLock(productSetId).orElseThrow(() -> ResourceNotFoundException.productSet(productSetId));

        return versions.findDraft(productSetId)
                .orElseGet(() -> branchFromLatestApproved(command));
    }

    private ProductSetVersion branchFromLatestApproved(OpenDraftCommand command) {
        ProductSetId productSetId = command.productSetId();
        ProductSetVersion latestApproved = versions.findLatestApproved(productSetId)
                .orElseThrow(() -> ResourceNotFoundException.approvedVersion(productSetId));
        ProductSetVersion draft = latestApproved.branchDraft(versions.nextVersionNumber(productSetId), command.requestedBy());
        return versions.add(draft);
    }
}
