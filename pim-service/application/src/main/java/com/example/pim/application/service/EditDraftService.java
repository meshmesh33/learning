package com.example.pim.application.service;

import com.example.pim.application.port.in.EditDraftUseCase;
import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.domain.model.ProductSetVersion;

public class EditDraftService implements EditDraftUseCase {

    private final VersionLookup lookup;

    public EditDraftService(ProductSetVersionRepository versions) {
        this.lookup = new VersionLookup(versions);
    }

    @Override
    public ProductSetVersion replaceTemplate(ReplaceTemplateCommand command) {
        return lookup.change(command.versionId(), command.expectedLockVersion(),
                draft -> draft.replaceTemplate(command.template()));
    }

    @Override
    public ProductSetVersion putVariation(PutVariationCommand command) {
        return lookup.change(command.versionId(), command.expectedLockVersion(),
                draft -> draft.putVariation(command.key(), command.attributes()));
    }

    @Override
    public ProductSetVersion removeVariation(RemoveVariationCommand command) {
        return lookup.change(command.versionId(), command.expectedLockVersion(),
                draft -> draft.removeVariation(command.key()));
    }
}
