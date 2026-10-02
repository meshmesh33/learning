package com.example.pim.application.port.in;

import com.example.pim.domain.model.Attributes;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.SellerId;
import com.example.pim.domain.model.UserId;
import com.example.pim.domain.model.VariationKey;

import java.util.Map;

public interface CreateProductSetUseCase {

    /** Registers a new product set and returns its first draft (v1). */
    ProductSetVersion create(CreateProductSetCommand command);

    record CreateProductSetCommand(SellerId sellerId,
                                   Attributes template,
                                   Map<VariationKey, Attributes> variations,
                                   UserId createdBy) {
    }
}
