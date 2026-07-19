package com.secureclipboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.secureclipboard.domain.Clipboard;
import com.secureclipboard.domain.ClipboardStatus;
import com.secureclipboard.domain.User;
import com.secureclipboard.repository.ClipboardRepository;
import com.secureclipboard.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
class UserControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClipboardRepository clipboardRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String uniqueEmail() {
        return "ctrl-" + UUID.randomUUID() + "@example.com";
    }

    private String uniqueCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private MockHttpSession loginSession(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();

        return (MockHttpSession) result.getRequest().getSession();
    }

    private Clipboard createClipboard(User owner, ClipboardStatus status) {
        Clipboard c = new Clipboard();
        c.setClipboardCode(uniqueCode());
        c.setSalt("testsalt");
        c.setPasswordHash("testhash");
        c.setExpiresAt(Instant.now().plus(Duration.ofHours(24)));
        c.setStatus(status);
        c.setOwner(owner);
        return clipboardRepository.save(c);
    }

    @Test
    void shouldReturn401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/users/me/clipboards"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnEmptyListWhenUserHasNoClipboards() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");

        mockMvc.perform(get("/api/users/me/clipboards").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0))
            .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void shouldReturnOwnedClipboards() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");
        User user = userRepository.findByEmail(email).orElseThrow();

        createClipboard(user, ClipboardStatus.ACTIVE);
        createClipboard(user, ClipboardStatus.ACTIVE);

        mockMvc.perform(get("/api/users/me/clipboards").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.items[0].clipboardCode").isString())
            .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.items[0].createdAt").isString())
            .andExpect(jsonPath("$.items[0].expiresAt").isString());
    }

    @Test
    void shouldExcludeDeletedClipboards() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");
        User user = userRepository.findByEmail(email).orElseThrow();

        createClipboard(user, ClipboardStatus.ACTIVE);
        createClipboard(user, ClipboardStatus.DELETED);
        createClipboard(user, ClipboardStatus.EXPIRED);

        mockMvc.perform(get("/api/users/me/clipboards").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void shouldNotReturnOtherUsersClipboards() throws Exception {

        String emailA = uniqueEmail();
        MockHttpSession sessionA = loginSession(emailA, "password123");
        User userA = userRepository.findByEmail(emailA).orElseThrow();
        createClipboard(userA, ClipboardStatus.ACTIVE);

        String emailB = uniqueEmail();
        MockHttpSession sessionB = loginSession(emailB, "password123");
        User userB = userRepository.findByEmail(emailB).orElseThrow();
        createClipboard(userB, ClipboardStatus.ACTIVE);
        createClipboard(userB, ClipboardStatus.ACTIVE);

        mockMvc.perform(get("/api/users/me/clipboards").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/users/me/clipboards").session(sessionB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void shouldPaginateResults() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");
        User user = userRepository.findByEmail(email).orElseThrow();

        for (int i = 0; i < 5; i++) {
            createClipboard(user, ClipboardStatus.ACTIVE);
        }

        mockMvc.perform(get("/api/users/me/clipboards?page=0&size=3").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.totalElements").value(5))
            .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/users/me/clipboards?page=1&size=3").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void shouldCapSizeAt100() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");

        // size=999 must be silently capped to 100 — no exception
        mockMvc.perform(get("/api/users/me/clipboards?size=999").session(session))
            .andExpect(status().isOk());
    }

    @Test
    void shouldReturn403WhenDeletingAnotherUsersClipboard() throws Exception {

        String emailA = uniqueEmail();
        MockHttpSession sessionA = loginSession(emailA, "password123");
        User userA = userRepository.findByEmail(emailA).orElseThrow();
        Clipboard clipboard = createClipboard(userA, ClipboardStatus.ACTIVE);

        String emailB = uniqueEmail();
        MockHttpSession sessionB = loginSession(emailB, "password123");

        mockMvc.perform(delete("/api/clipboards/" + clipboard.getClipboardCode())
                .session(sessionB))
            .andExpect(status().isForbidden());
    }

    @Test
    void createdClipboardShouldAppearInUserList() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");

        MvcResult createResult = mockMvc.perform(post("/api/clipboards")
                .contentType(MediaType.APPLICATION_JSON)
                .session(session))
            .andExpect(status().isCreated())
            .andReturn();

        String code = new ObjectMapper()
            .readTree(createResult.getResponse().getContentAsString())
            .get("clipboardCode").asText();

        mockMvc.perform(get("/api/users/me/clipboards").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].clipboardCode").value(code));
    }
    // Verifies that requesting a page beyond the available data returns an empty result.
    @Test
    void shouldReturnEmptySecondPage() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");
        User user = userRepository.findByEmail(email).orElseThrow();

        createClipboard(user, ClipboardStatus.ACTIVE);

        mockMvc.perform(get("/api/users/me/clipboards?page=1&size=10").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // Verifies that expired clipboards remain visible in the user's list.
    @Test
    void shouldReturnOnlyExpiredClipboardWhenItIsTheOnlyOne() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");
        User user = userRepository.findByEmail(email).orElseThrow();

        createClipboard(user, ClipboardStatus.EXPIRED);

        mockMvc.perform(get("/api/users/me/clipboards").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("EXPIRED"));
    }

    // Verifies that the owner can delete their own clipboard.
    @Test
    void shouldAllowOwnerToDeleteOwnClipboard() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");
        User user = userRepository.findByEmail(email).orElseThrow();

        Clipboard clipboard = createClipboard(user, ClipboardStatus.ACTIVE);

        mockMvc.perform(delete("/api/clipboards/" + clipboard.getClipboardCode())
                        .session(session))
                .andExpect(status().isNoContent());
    }

    // Verifies that the returned clipboard code matches the stored clipboard.
    @Test
    void shouldReturnCorrectClipboardCodeInList() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");
        User user = userRepository.findByEmail(email).orElseThrow();

        Clipboard clipboard = createClipboard(user, ClipboardStatus.ACTIVE);

        mockMvc.perform(get("/api/users/me/clipboards").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].clipboardCode").value(clipboard.getClipboardCode()));
    }

    // Verifies that pagination metadata is correct for a single clipboard.
    @Test
    void shouldReturnCorrectPaginationMetadataForSingleClipboard() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");
        User user = userRepository.findByEmail(email).orElseThrow();

        createClipboard(user, ClipboardStatus.ACTIVE);

        mockMvc.perform(get("/api/users/me/clipboards?page=0&size=10").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    // Verifies that deleted clipboards are excluded from the user list.
    @Test
    void shouldReturnEmptyResultForUserWithoutActiveClipboards() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");
        User user = userRepository.findByEmail(email).orElseThrow();

        createClipboard(user, ClipboardStatus.DELETED);

        mockMvc.perform(get("/api/users/me/clipboards").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // Verifies that the clipboard status is returned correctly.
    @Test
    void shouldReturnClipboardStatusForActiveClipboard() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");
        User user = userRepository.findByEmail(email).orElseThrow();

        createClipboard(user, ClipboardStatus.ACTIVE);

        mockMvc.perform(get("/api/users/me/clipboards").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
    }

    // Verifies that repeated requests return consistent clipboard data.
    @Test
    void shouldNotChangeClipboardListAfterRepeatedRequests() throws Exception {
        String email = uniqueEmail();
        MockHttpSession session = loginSession(email, "password123");
        User user = userRepository.findByEmail(email).orElseThrow();

        createClipboard(user, ClipboardStatus.ACTIVE);
        createClipboard(user, ClipboardStatus.ACTIVE);

        mockMvc.perform(get("/api/users/me/clipboards").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/users/me/clipboards").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void guestCanStillCreateClipboardWhileUserIsLoggedIn() throws Exception {
        MockHttpSession session = loginSession(uniqueEmail(), "password123");

        mockMvc.perform(post("/api/clipboards").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.clipboardCode").isNotEmpty());

        mockMvc.perform(post("/api/clipboards")
                .contentType(MediaType.APPLICATION_JSON)
                .session(session))
            .andExpect(status().isCreated());
    }
}
