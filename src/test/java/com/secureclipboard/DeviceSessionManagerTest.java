package com.secureclipboard;

import com.secureclipboard.service.DeviceSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.clipboard.max-devices=2")
class DeviceSessionManagerTest {

    @Autowired
    private DeviceSessionManager sessionManager;

    private WebSocketSession mockSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void join_belowLimit_succeeds() {
        String code = "test-" + UUID.randomUUID();
        sessionManager.join(code, "d1", mockSession("s1"));
        assertEquals(1, sessionManager.getConnectedCount(code));
    }

    @Test
    void join_atLimit_throwsException() {
        String code = "test-" + UUID.randomUUID();
        sessionManager.join(code, "d1", mockSession("s1"));
        sessionManager.join(code, "d2", mockSession("s2"));
        assertThrows(IllegalStateException.class, () ->
            sessionManager.join(code, "d3", mockSession("s3")));
    }

    @Test
    void join_afterLeave_succeeds() {
        String code = "test-" + UUID.randomUUID();
        WebSocketSession s1 = mockSession("s1");
        sessionManager.join(code, "d1", s1);
        sessionManager.join(code, "d2", mockSession("s2"));
        sessionManager.leave(s1);
        assertDoesNotThrow(() ->
            sessionManager.join(code, "d3", mockSession("s3")));
    }
}
