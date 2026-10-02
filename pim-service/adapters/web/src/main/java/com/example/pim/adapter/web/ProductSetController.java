package com.example.pim.adapter.web;

import com.example.pim.adapter.web.WebDtos.CreateProductSetRequest;
import com.example.pim.adapter.web.WebDtos.VersionResponse;
import com.example.pim.application.port.in.CreateProductSetUseCase;
import com.example.pim.application.port.in.CreateProductSetUseCase.CreateProductSetCommand;
import com.example.pim.application.port.in.OpenDraftUseCase;
import com.example.pim.application.port.in.OpenDraftUseCase.OpenDraftCommand;
import com.example.pim.application.port.in.ProductSetQueries;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetId;
import com.example.pim.domain.model.SellerId;
import com.example.pim.domain.model.UserId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.SortedMap;
import java.util.UUID;

@RestController
@RequestMapping("/product-sets")
class ProductSetController {

    static final String USER_HEADER = "X-User";

    private final CreateProductSetUseCase createProductSet;
    private final OpenDraftUseCase openDraft;
    private final ProductSetQueries queries;

    ProductSetController(CreateProductSetUseCase createProductSet, OpenDraftUseCase openDraft, ProductSetQueries queries) {
        this.createProductSet = createProductSet;
        this.openDraft = openDraft;
        this.queries = queries;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    VersionResponse create(@RequestBody CreateProductSetRequest request, @RequestHeader(USER_HEADER) String user) {
        return VersionResponse.from(createProductSet.create(new CreateProductSetCommand(
                new SellerId(request.sellerId()), request.templateAttributes(), request.variationAttributes(),
                new UserId(user))));
    }

    @PostMapping("/{productSetId}/draft")
    VersionResponse openDraft(@PathVariable UUID productSetId, @RequestHeader(USER_HEADER) String user) {
        return VersionResponse.from(openDraft.openDraft(new OpenDraftCommand(new ProductSetId(productSetId), new UserId(user))));
    }

    @GetMapping("/{productSetId}/countries")
    SortedMap<String, UUID> countryAssignments(@PathVariable UUID productSetId) {
        return WebDtos.countryAssignments(queries.countryAssignments(new ProductSetId(productSetId)));
    }

    @GetMapping("/{productSetId}/countries/{country}")
    VersionResponse liveVersion(@PathVariable UUID productSetId, @PathVariable String country) {
        return VersionResponse.from(queries.liveVersion(new ProductSetId(productSetId), new CountryCode(country)));
    }
}
