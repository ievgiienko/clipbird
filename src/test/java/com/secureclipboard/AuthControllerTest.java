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
    // Verifies that registration fails when the password is empty.
    @Test
    void shouldRejectEmptyPasswordOnRegistration() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(uniqueEmail(), "")))
                .andExpect(status().isBadRequest());
    }

    // Verifies that registration fails when the email is empty.
    @Test
    void shouldRejectEmptyEmailOnRegistration() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson("", "password123")))
                .andExpect(status().isBadRequest());
    }

    // Verifies that login validation rejects empty credentials.
    @Test
    void shouldRejectEmptyCredentialsOnLogin() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson("", "")))
                .andExpect(status().isBadRequest());
    }

    // Verifies that the current user is cleared after logout.
    @Test
    void shouldReturnNoContentAfterLogoutWhenCheckingCurrentUser() throws Exception {
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

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isNoContent());
    }

    // Verifies that a failed login attempt does not prevent a later successful login.
    @Test
    void shouldAllowLoginAfterFailedAttempt() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email, "password123")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email, "wrongpassword")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email, "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    // Verifies that different users can authenticate independently.
    @Test
    void shouldCreateIndependentSessionsForDifferentUsers() throws Exception {
        String email1 = uniqueEmail();
        String email2 = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email1, "password123")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email2, "password123")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email1, "password123")))
                .andExpect(status().isOk());mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email2, "password123")))
                .andExpect(status().isOk());
    }

    // Verifies that the authenticated session remains valid across multiple requests.
    @Test
    void shouldKeepUserLoggedInAcrossMultipleMeRequests() throws Exception {
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
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }


    @Test
    void shouldAllowMultipleGuestClipboardCreations() throws Exception {
        mockMvc.perform(post("/api/clipboards")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/clipboards")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    // Verifies that logging out one user does not affect another active session.
    @Test
    void shouldNotInvalidateAnotherUserSessionAfterLogout() throws Exception {
        String email1 = uniqueEmail();
        String email2 = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email1, "password123")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email2, "password123")))
                .andExpect(status().isCreated());

        MvcResult login1 = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email1, "password123")))
                .andReturn();

        MvcResult login2 = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email2, "password123")))
                .andReturn();

        MockHttpSession session1 = (MockHttpSession) login1.getRequest().getSession();
        MockHttpSession session2 = (MockHttpSession) login2.getRequest().getSession();

        mockMvc.perform(post("/api/auth/logout").session(session1))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").session(session2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email2));
    }

    // Verifies that repeated requests return the same authenticated user.
    @Test
    void shouldReturnSameUserForRepeatedMeRequests() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email, "password123")))
                .andExpect(status().isCreated());MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email, "password123")))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/auth/me").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(email));
        }
    }

    // Verifies that registration rejects a blank email value
    @Test
    void shouldRejectRegistrationWithBlankEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"   \",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest());
    }

    // Verifies that registration rejects a blank password value.
    @Test
    void shouldRejectRegistrationWithBlankPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail() + "\",\"password\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    // Verifies that a user can log in again after logging out.
    @Test
    void shouldAllowLoginAfterLogout() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email, "password123")))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email, "password123")))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

        mockMvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email, "password123")))
                .andExpect(status().isOk());
    }

    // Verifies that repeated logout requests remain idempotent.
    @Test
    void shouldRejectSecondLogoutGracefully() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email, "password123")))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email, "password123")))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

        mockMvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isNoContent());
    }

    // Verifies that multiple users can be registered with unique email addresses.
    @Test
    void shouldCreateUniqueUsers() throws Exception {
        String email1 = uniqueEmail();
        String email2 = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email1, "password123")))
                .andExpect(status().isCreated());mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authJson(email2, "password123")))
                .andExpect(status().isCreated());
    }

    // Verifies that an unauthenticated session is treated as a guest session.
    @Test
    void shouldNotAuthenticateGuestSession() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isNoContent());
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
