package com.secureclipboard;

import com.secureclipboard.domain.User;
import com.secureclipboard.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    private User buildUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$10$hashedpassword");
        return user;
    }

    @Test
    void shouldSaveAndFindByEmail() {
        User saved = userRepository.save(buildUser("alice@example.com"));

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());

        Optional<User> found = userRepository.findByEmail("alice@example.com");
        assertTrue(found.isPresent());
        assertEquals(saved.getId(), found.get().getId());
        assertEquals("alice@example.com", found.get().getEmail());
    }

    @Test
    void shouldReturnEmptyForUnknownEmail() {
        Optional<User> result = userRepository.findByEmail("nobody@example.com");
        assertFalse(result.isPresent());
    }

    @Test
    void existsByEmailShouldReturnTrueWhenExists() {
        userRepository.save(buildUser("bob@example.com"));

        assertTrue(userRepository.existsByEmail("bob@example.com"));
        assertFalse(userRepository.existsByEmail("other@example.com"));
    }

    @Test
    void shouldRejectDuplicateEmail() {
        userRepository.save(buildUser("carol@example.com"));

        User duplicate = buildUser("carol@example.com");
        assertThrows(DataIntegrityViolationException.class,
            () -> userRepository.saveAndFlush(duplicate));
    }
    // Verifies that each saved user receives a unique identifier.
    @Test
    void shouldAssignDifferentIdsToDifferentUsers() {
        User first = userRepository.save(buildUser("user1@example.com"));
        User second = userRepository.save(buildUser("user2@example.com"));

        assertNotEquals(first.getId(), second.getId());
    }

    // Verifies that the stored email is returned without modification.
    @Test
    void shouldPreserveEmailExactlyAsSaved() {
        User saved = userRepository.save(buildUser("CaseSensitive@example.com"));

        User found = userRepository.findByEmail("CaseSensitive@example.com").orElseThrow();

        assertEquals("CaseSensitive@example.com", found.getEmail());
    }

    // Verifies that a newly saved user is immediately available for repository queries.
    @Test
    void shouldFindRecentlySavedUser() {
        String email = "recent@example.com";

        userRepository.save(buildUser(email));

        assertTrue(userRepository.findByEmail(email).isPresent());
        assertTrue(userRepository.existsByEmail(email));
    }

    // Verifies that the repository assigns a valid creation timestamp to new users.
    @Test
    void shouldStoreCreationTimestamp() {
        User saved = userRepository.save(buildUser("timestamp@example.com"));

        assertNotNull(saved.getCreatedAt());
        assertFalse(saved.getCreatedAt().isAfter(java.time.Instant.now()));
    }
    @Test
    void shouldPersistPasswordHash() {
        User user = buildUser("dave@example.com");
        user.setPasswordHash("$2a$10$specificHash");
        userRepository.save(user);

        User found = userRepository.findByEmail("dave@example.com").orElseThrow();
        assertEquals("$2a$10$specificHash", found.getPasswordHash());
    }
}
