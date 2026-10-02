package com.example.pim.adapter.web;

import com.example.pim.domain.event.ProductSetVersionPublished;
import com.example.pim.domain.model.Attributes;
import com.example.pim.domain.model.CountryCode;
import com.example.pim.domain.model.ProductSetVersion;
import com.example.pim.domain.model.VariationKey;
import com.example.pim.domain.model.VersionDiff;
import com.example.pim.domain.model.VersionId;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** HTTP request/response shapes and their mapping to and from the domain. */
final class WebDtos {

    private WebDtos() {
    }

    record CreateProductSetRequest(String sellerId,
                                   Map<String, Object> template,
                                   Map<String, Map<String, Object>> variations) {

        Attributes templateAttributes() {
            return template == null ? Attributes.empty() : Attributes.of(template);
        }

        Map<VariationKey, Attributes> variationAttributes() {
            if (variations == null) {
                return Map.of();
            }
            return variations.entrySet().stream()
                    .collect(Collectors.toMap(e -> new VariationKey(e.getKey()), e -> Attributes.of(e.getValue())));
        }
    }

    record PublishRequest(List<String> countries) {

        Set<CountryCode> countryCodes() {
            return countries == null ? Set.of() : countries.stream().map(CountryCode::new).collect(Collectors.toSet());
        }
    }

    record VersionResponse(UUID productSetId,
                           UUID versionId,
                           int versionNo,
                           String status,
                           int lockVersion,
                           UUID basedOnVersionId,
                           Map<String, Object> template,
                           SortedMap<String, Map<String, Object>> variations) {

        static VersionResponse from(ProductSetVersion version) {
            SortedMap<String, Map<String, Object>> variations = new TreeMap<>();
            version.variations().forEach((key, attributes) -> variations.put(key.value(), attributes.values()));
            return new VersionResponse(version.productSetId().value(), version.id().value(), version.number().value(),
                    version.status().name(), version.lockVersion(),
                    version.basedOn().map(VersionId::value).orElse(null),
                    version.template().values(), variations);
        }
    }

    record PublicationResponse(long publishSeq, UUID versionId, int versionNo, List<String> countries, Instant publishedAt) {

        static PublicationResponse from(ProductSetVersionPublished event) {
            return new PublicationResponse(event.sequence().value(), event.versionId().value(),
                    event.versionNumber().value(), event.countries().stream().map(CountryCode::value).toList(),
                    event.publishedAt());
        }
    }

    record DiffResponse(boolean templateChanged, List<String> added, List<String> removed, List<String> changed) {

        static DiffResponse from(VersionDiff diff) {
            return new DiffResponse(diff.templateChanged(), keys(diff.addedVariations()),
                    keys(diff.removedVariations()), keys(diff.changedVariations()));
        }

        private static List<String> keys(Collection<VariationKey> keys) {
            return keys.stream().map(VariationKey::value).toList();
        }
    }

    static SortedMap<String, UUID> countryAssignments(SortedMap<CountryCode, VersionId> assignments) {
        SortedMap<String, UUID> response = new TreeMap<>();
        assignments.forEach((country, version) -> response.put(country.value(), version.value()));
        return response;
    }
}
