package com.example.pim.application.port.in;

import com.example.pim.domain.model.Attributes;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.VariationKey;
import com.example.pim.domain.model.VersionId;

/**
 * Every command carries the {@code expectedLockVersion} the caller last read, so two editors
 * cannot silently overwrite each other.
 */
public interface EditDraftUseCase {

    ProductSetVersion replaceTemplate(ReplaceTemplateCommand command);

    ProductSetVersion putVariation(PutVariationCommand command);

    ProductSetVersion removeVariation(RemoveVariationCommand command);

    record ReplaceTemplateCommand(VersionId versionId, int expectedLockVersion, Attributes template) {
    }

    record PutVariationCommand(VersionId versionId, int expectedLockVersion, VariationKey key, Attributes attributes) {
    }

    record RemoveVariationCommand(VersionId versionId, int expectedLockVersion, VariationKey key) {
    }
}
