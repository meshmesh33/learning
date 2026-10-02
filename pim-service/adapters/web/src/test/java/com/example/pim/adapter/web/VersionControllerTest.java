package com.example.pim.adapter.web;

import com.example.pim.application.port.in.CreateProductSetUseCase;
import com.example.pim.application.port.in.EditDraftUseCase;
import com.example.pim.application.port.in.OpenDraftUseCase;
import com.example.pim.application.port.in.ProductSetQueries;
import com.example.pim.application.port.in.PublishVersionUseCase;
import com.example.pim.application.port.in.ReviewVersionUseCase;
import com.example.pim.domain.exception.StaleVersionException;
import com.example.pim.domain.model.VersionId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers the HTTP translation only: use cases are mocked. */
@WebMvcTest({ProductSetController.class, VersionController.class, ApiExceptionHandler.class})
class VersionControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CreateProductSetUseCase createProductSet;
    @MockitoBean
    OpenDraftUseCase openDraft;
    @MockitoBean
    EditDraftUseCase editDraft;
    @MockitoBean
    ReviewVersionUseCase review;
    @MockitoBean
    PublishVersionUseCase publish;
    @MockitoBean
    ProductSetQueries queries;

    private final UUID versionId = UUID.randomUUID();

    @Test
    void staleLockVersionIsAConflict() throws Exception {
        when(editDraft.putVariation(any())).thenThrow(new StaleVersionException(new VersionId(versionId), 0, 1));

        mvc.perform(put("/versions/{id}/variations/M?lockVersion=0", versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\": 11}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("modified concurrently")));
    }

    @Test
    void invalidCountryIsABadRequestAndNeverReachesTheUseCase() throws Exception {
        mvc.perform(post("/versions/{id}/publish", versionId)
                        .header("X-User", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countries\": [\"EGYPT\"]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(publish);
    }

    @Test
    void missingUserHeaderIsRejected() throws Exception {
        mvc.perform(post("/versions/{id}/approve?lockVersion=0", versionId))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(review);
    }
}
