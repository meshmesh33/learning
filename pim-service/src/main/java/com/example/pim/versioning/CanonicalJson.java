package com.example.pim.versioning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Serialises attribute maps with sorted keys so that equal content always produces the same
 * bytes, and therefore the same SHA-256 hash. The hash is what lets versions share revisions.
 */
@Component
public class CanonicalJson {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public record Canonical(String json, byte[] hash) {
    }

    public Canonical canonicalize(Map<String, Object> attributes) {
        try {
            // Round-trip through a plain Map so nested objects are maps too and get sorted.
            Map<String, Object> normalized = mapper.convertValue(attributes, MAP);
            String json = mapper.writeValueAsString(normalized);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return new Canonical(json, hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot canonicalize attributes", e);
        }
    }

    public Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored JSON", e);
        }
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise value", e);
        }
    }
}
