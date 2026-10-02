package com.example.pim.api;

import com.example.pim.outbox.ProductSetPublishedEvent;
import com.example.pim.versioning.ProductSetSnapshot;
import com.example.pim.versioning.ProductVersionService;
import com.example.pim.versioning.VersionDiff;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seller-facing API. {@code lockVersion} comes from the last snapshot the caller read; a mismatch
 * returns 409 so two editors cannot silently overwrite each other's draft changes.
 */
@RestController
public class ProductSetController {

    public record CreateProductSetRequest(String sellerId,
                                          Map<String, Object> template,
                                          Map<String, Map<String, Object>> variations) {
    }

    public record PublishRequest(List<String> countries) {
    }

    private final ProductVersionService service;

    public ProductSetController(ProductVersionService service) {
        this.service = service;
    }

    @PostMapping("/product-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductSetSnapshot create(@RequestBody CreateProductSetRequest request,
                                     @RequestHeader("X-User") String user) {
        return service.createProductSet(request.sellerId(), request.template(),
                request.variations() == null ? Map.of() : request.variations(), user);
    }

    @PostMapping("/product-sets/{productSetId}/draft")
    public ProductSetSnapshot openDraft(@PathVariable UUID productSetId, @RequestHeader("X-User") String user) {
        return service.openDraft(productSetId, user);
    }

    @GetMapping("/product-sets/{productSetId}/countries")
    public Map<String, Long> countries(@PathVariable UUID productSetId) {
        return service.countryPointers(productSetId);
    }

    @GetMapping("/product-sets/{productSetId}/countries/{country}")
    public ProductSetSnapshot forCountry(@PathVariable UUID productSetId, @PathVariable String country) {
        return service.getForCountry(productSetId, country);
    }

    @GetMapping("/versions/{versionId}")
    public ProductSetSnapshot get(@PathVariable long versionId) {
        return service.getVersion(versionId);
    }

    @PutMapping("/versions/{versionId}/template")
    public ProductSetSnapshot updateTemplate(@PathVariable long versionId,
                                             @RequestParam int lockVersion,
                                             @RequestBody Map<String, Object> attributes) {
        return service.updateTemplate(versionId, lockVersion, attributes);
    }

    @PutMapping("/versions/{versionId}/variations/{key}")
    public ProductSetSnapshot putVariation(@PathVariable long versionId,
                                           @PathVariable String key,
                                           @RequestParam int lockVersion,
                                           @RequestBody Map<String, Object> attributes) {
        return service.putVariation(versionId, lockVersion, key, attributes);
    }

    @DeleteMapping("/versions/{versionId}/variations/{key}")
    public ProductSetSnapshot removeVariation(@PathVariable long versionId,
                                              @PathVariable String key,
                                              @RequestParam int lockVersion) {
        return service.removeVariation(versionId, lockVersion, key);
    }

    @PostMapping("/versions/{versionId}/approve")
    public ProductSetSnapshot approve(@PathVariable long versionId,
                                      @RequestParam int lockVersion,
                                      @RequestHeader("X-User") String user) {
        return service.approve(versionId, lockVersion, user);
    }

    @PostMapping("/versions/{versionId}/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable long versionId, @RequestParam int lockVersion) {
        service.discard(versionId, lockVersion);
    }

    @PostMapping("/versions/{versionId}/publish")
    public ProductSetPublishedEvent publish(@PathVariable long versionId,
                                            @RequestBody PublishRequest request,
                                            @RequestHeader("X-User") String user) {
        return service.publish(versionId, request.countries(), user);
    }

    @GetMapping("/versions/{fromVersionId}/diff/{toVersionId}")
    public VersionDiff diff(@PathVariable long fromVersionId, @PathVariable long toVersionId) {
        return service.diff(fromVersionId, toVersionId);
    }
}
