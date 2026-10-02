package com.example.pim.application.service;

import com.example.pim.application.port.in.CreateProductSetUseCase;
import com.example.pim.application.port.out.ProductSetRepository;
import com.example.pim.application.port.out.ProductSetVersionRepository;
import com.example.pim.domain.model.ProductSet;
import com.example.pim.domain.model.ProductSetVersion;

public class CreateProductSetService implements CreateProductSetUseCase {

    private final ProductSetRepository productSets;
    private final ProductSetVersionRepository versions;

    public CreateProductSetService(ProductSetRepository productSets, ProductSetVersionRepository versions) {
        this.productSets = productSets;
        this.versions = versions;
    }

    @Override
    public ProductSetVersion create(CreateProductSetCommand command) {
        ProductSet productSet = ProductSet.register(command.sellerId());
        productSets.add(productSet);
        ProductSetVersion firstDraft = ProductSetVersion.firstDraft(
                productSet.id(), command.template(), command.variations(), command.createdBy());
        return versions.add(firstDraft);
    }
}
