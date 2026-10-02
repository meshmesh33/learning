package com.example.pim.adapter.persistence;

import com.example.pim.domain.model.Attributes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Serialises attributes with sorted keys so that equal content always yields the same bytes and
 * therefore the same SHA-256 hash: the key that lets versions share stored revisions.
 */
@Component
class CanonicalJson {

    record Canonical(String json, byte[] hash) {
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    Canonical canonicalize(Attributes attributes) {
        try {
            String json = mapper.writeValueAsString(attributes.values());
            return new Canonical(json, sha256(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Attributes are not serialisable as JSON", e);
        }
    }

    Attributes parse(String json) {
        try {
            return new Attributes(mapper.readValue(json, MAP));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored attributes are not valid JSON", e);
        }
    }

    private static byte[] sha256(String json) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
