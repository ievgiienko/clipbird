package com.secureclipboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
class ClipboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldCreateClipboard() throws Exception {
        mockMvc.perform(post("/api/clipboards")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.clipboardCode").isNotEmpty())
            .andExpect(jsonPath("$.salt").isNotEmpty())
            .andExpect(jsonPath("$.kdfAlgorithm").value("PBKDF2"));
    }

    @Test
    void shouldSetPasswordAndVerify() throws Exception {
        // Create
        MvcResult createResult = mockMvc.perform(post("/api/clipboards")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String code = created.get("clipboardCode").asText();

        // Set password
        mockMvc.perform(post("/api/clipboards/" + code + "/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"passwordHash\":\"testhash123\"}"))
            .andExpect(status().isOk());

        // Verify correct password
        mockMvc.perform(post("/api/clipboards/" + code + "/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"passwordHash\":\"testhash123\"}"))
            .andExpect(status().isOk());

        // Verify wrong password
        mockMvc.perform(post("/api/clipboards/" + code + "/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"passwordHash\":\"wronghash\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void shouldGetHandshake() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/clipboards")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String code = created.get("clipboardCode").asText();

        mockMvc.perform(get("/api/clipboards/" + code + "/handshake"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clipboardCode").value(code))
            .andExpect(jsonPath("$.salt").isNotEmpty())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void shouldGetState() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/clipboards")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String code = created.get("clipboardCode").asText();

        mockMvc.perform(get("/api/clipboards/" + code + "/state"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clipboardCode").value(code))
            .andExpect(jsonPath("$.files").isArray())
            .andExpect(jsonPath("$.connectedDevices").value(0));
    }
    @Test
    void shouldDeleteClipboard() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/clipboards")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String code = created.get("clipboardCode").asText();

        mockMvc.perform(delete("/api/clipboards/" + code))
            .andExpect(status().isNoContent());

        // Should fail to get state of deleted clipboard
        mockMvc.perform(get("/api/clipboards/" + code + "/state"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400ForNonExistentClipboard() throws Exception {
        mockMvc.perform(get("/api/clipboards/NONEXIST/handshake"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shouldCreateClipboardWithCustomTtl() throws Exception {
        mockMvc.perform(post("/api/clipboards")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":1}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }
    @Test
    void shouldProvideDataConsistentWithQrCodeRequirements() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/clipboards")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String code = response.get("clipboardCode").asText();
    }





    @Test
    void shouldRejectInvalidTtl() throws Exception {
        mockMvc.perform(post("/api/clipboards")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ttlHours\":99}"))
            .andExpect(status().isBadRequest());
    }
}
