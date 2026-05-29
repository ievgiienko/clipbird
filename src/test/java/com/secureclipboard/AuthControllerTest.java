package com.secureclipboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private String authJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    // Register

    @Test
    void shouldRegisterNewUser() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson(email, "password123")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").isString())
            .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void shouldRejectDuplicateEmail() throws Exception {
        String email = uniqueEmail();
        String body = authJson(email, "password123");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict());
    }

    @Test
    void shouldRejectPasswordShorterThan8Chars() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson(uniqueEmail(), "short")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInvalidEmailFormat() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson("not-an-email", "password123")))
            .andExpect(status().isBadRequest());
    }

    // Login

    @Test
    void shouldLoginWithValidCredentials() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson(email, "password123")))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson(email, "password123")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.email").isString());
    }

    @Test
    void shouldRejectWrongPassword() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson(email, "password123")))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson(email, "wrongpassword")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectLoginForUnknownEmail() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson("nobody-" + UUID.randomUUID() + "@example.com", "password123")))
            .andExpect(status().isUnauthorized());
    }

    // me

    @Test
    void meReturnsNoContentForGuest() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isNoContent());
    }

    @Test
    void shouldReturnCurrentUserAfterLogin() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson(email, "password123")))
            .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson(email, "password123")))
            .andExpect(status().isOk())
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

        mockMvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.email").isString());
    }

    // Logout

    @Test
    void shouldLogoutSuccessfully() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson(email, "password123")))
            .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson(email, "password123")))
            .andExpect(status().isOk())
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

        mockMvc.perform(post("/api/auth/logout").session(session))
            .andExpect(status().isNoContent());
    }

    @Test
    void logoutWithoutSessionShouldBeIdempotent() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
            .andExpect(status().isNoContent());
    }

    // Guest clipboard flow must stay unchanged

    @Test
    void guestCanCreateClipboardWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/clipboards")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.clipboardCode").isNotEmpty())
            .andExpect(jsonPath("$.salt").isNotEmpty());
    }

    @Test
    void guestCanVerifyClipboardPasswordWithoutAuthentication() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/clipboards")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn();

        String body = createResult.getResponse().getContentAsString();
        String code = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(body).get("clipboardCode").asText();

        mockMvc.perform(post("/api/clipboards/" + code + "/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"passwordHash\":\"testhash123\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/clipboards/" + code + "/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"passwordHash\":\"testhash123\"}"))
            .andExpect(status().isOk());
    }
}
