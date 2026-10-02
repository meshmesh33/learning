package com.example.pim.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full stack over HTTP against real PostgreSQL; Kafka is not needed because the relay is off. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductSetVersioningEndToEndTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE outbox_event, country_version, version_variation, product_set_version, "
                 + "variation_revision, template_revision, product_set CASCADE").update();
    }

    private JsonNode call(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(request.header("X-User", "alice").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : objectMapper.readTree(body);
    }

    private long outboxEvents() {
        return jdbc.sql("SELECT count(*) FROM outbox_event").query(Long.class).single();
    }

    @Test
    void sellerVersionsAProductSetAndCountriesServeDifferentVersions() throws Exception {
        JsonNode v1 = call(post("/product-sets").content("""
                {"sellerId": "seller-1",
                 "template": {"name": "Basic tee", "material": "cotton"},
                 "variations": {"S": {"price": 10}, "M": {"price": 10}, "L": {"price": 12}}}
                """), 201);
        String setId = v1.get("productSetId").asText();
        String v1Id = v1.get("versionId").asText();
        assertThat(v1.get("status").asText()).isEqualTo("DRAFT");
        assertThat(outboxEvents()).as("drafts are never announced").isZero();

        call(post("/versions/{id}/approve?lockVersion=0", v1Id), 200);
        JsonNode firstPublication = call(post("/versions/{id}/publish", v1Id).content("{\"countries\": [\"EG\", \"SA\"]}"), 200);

        JsonNode v2 = call(post("/product-sets/{id}/draft", setId), 200);
        String v2Id = v2.get("versionId").asText();
        v2 = call(put("/versions/{id}/variations/M?lockVersion=0", v2Id).content("{\"price\": 11}"), 200);
        call(put("/versions/{id}/variations/M?lockVersion=0", v2Id).content("{\"price\": 12}"), 409);
        call(post("/versions/{id}/publish", v2Id).content("{\"countries\": [\"EG\"]}"), 409);
        call(post("/versions/{id}/approve?lockVersion={lock}", v2Id, v2.get("lockVersion").asInt()), 200);
        JsonNode secondPublication = call(post("/versions/{id}/publish", v2Id).content("{\"countries\": [\"EG\"]}"), 200);

        assertThat(call(get("/product-sets/{id}/countries/EG", setId), 200).get("versionNo").asInt()).isEqualTo(2);
        assertThat(call(get("/product-sets/{id}/countries/SA", setId), 200).get("versionNo").asInt()).isEqualTo(1);
        assertThat(call(get("/versions/{a}/diff/{b}", v1Id, v2Id), 200).get("changed").get(0).asText()).isEqualTo("M");
        assertThat(secondPublication.get("publishSeq").asLong()).isGreaterThan(firstPublication.get("publishSeq").asLong());
        assertThat(outboxEvents()).isEqualTo(2);
    }
}
