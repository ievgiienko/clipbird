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

    // Verifies that a joined device is reported as online.
    @Test
    void shouldReportDeviceOnlineAfterJoin() {
        String code = "test-" + UUID.randomUUID();

        sessionManager.join(code, "device1", mockSession("s1"));

        assertTrue(sessionManager.isDeviceOnline(code, "device1"));
    }

    // Verifies that an unknown device is reported as offline.
    @Test
    void shouldReportDeviceOfflineBeforeJoin() {
        String code = "test-" + UUID.randomUUID();

        assertFalse(sessionManager.isDeviceOnline(code, "device1"));
    }

    // Verifies that a device becomes offline after leaving.
    @Test
    void shouldReportDeviceOfflineAfterLeave() {
        String code = "test-" + UUID.randomUUID();

        WebSocketSession session = mockSession("s1");
        sessionManager.join(code, "device1", session);
        sessionManager.leave(session);

        assertFalse(sessionManager.isDeviceOnline(code, "device1"));
    }

    // Verifies that an unknown clipboard has no connected devices.
    @Test
    void shouldReturnZeroConnectedDevicesForUnknownClipboard() {
        String code = "test-" + UUID.randomUUID();

        assertEquals(0, sessionManager.getConnectedCount(code));
    }

    // Verifies that leaving an unknown session does not throw an exception.
    @Test
    void shouldIgnoreLeavingUnknownSession() {
        WebSocketSession session = mockSession("unknown");

        assertDoesNotThrow(() -> sessionManager.leave(session));
    }

    // Verifies that sessions from different clipboards are tracked independently.
    @Test
    void shouldKeepClipboardSessionsSeparated() {
        String code1 = "test-" + UUID.randomUUID();
        String code2 = "test-" + UUID.randomUUID();

        sessionManager.join(code1, "device1", mockSession("s1"));
        sessionManager.join(code2, "device2", mockSession("s2"));

        assertEquals(1, sessionManager.getConnectedCount(code1));
        assertEquals(1, sessionManager.getConnectedCount(code2));
    }

    // Verifies that the same device ID can exist in different clipboards.
    @Test
    void shouldAllowSameDeviceIdInDifferentClipboards() {
        String code1 = "test-" + UUID.randomUUID();
        String code2 = "test-" + UUID.randomUUID();

        sessionManager.join(code1, "device1", mockSession("s1"));
        sessionManager.join(code2, "device1", mockSession("s2"));

        assertTrue(sessionManager.isDeviceOnline(code1, "device1"));
        assertTrue(sessionManager.isDeviceOnline(code2, "device1"));
    }

    // Verifies that the connected device count decreases after a session leaves.
    @Test
    void shouldDecreaseConnectedCountAfterLeave() {
        String code = "test-" + UUID.randomUUID();

        WebSocketSession session = mockSession("s1");
        sessionManager.join(code, "device1", session);

        assertEquals(1, sessionManager.getConnectedCount(code));

        sessionManager.leave(session);

        assertEquals(0, sessionManager.getConnectedCount(code));
    }

    // Verifies that reconnecting the same device does not create duplicate connections.
    @Test
    void shouldReplacePreviousSessionForSameDevice() {
        String code = "test-" + UUID.randomUUID();

        WebSocketSession first = mockSession("s1");
        WebSocketSession second = mockSession("s2");

        sessionManager.join(code, "device1", first);
        sessionManager.join(code, "device1", second);

        assertEquals(1, sessionManager.getConnectedCount(code));
        assertTrue(sessionManager.isDeviceOnline(code, "device1"));
    }

    // Verifies that removing one device does not affect other connected devices.
    @Test
    void shouldKeepOtherDevicesConnectedAfterOneLeaves() {
        String code = "test-" + UUID.randomUUID();

        WebSocketSession first = mockSession("s1");
        WebSocketSession second = mockSession("s2");

        sessionManager.join(code, "device1", first);
        sessionManager.join(code, "device2", second);

        sessionManager.leave(first);

        assertEquals(1, sessionManager.getConnectedCount(code));
        assertTrue(sessionManager.isDeviceOnline(code, "device2"));
        assertFalse(sessionManager.isDeviceOnline(code, "device1"));
    }

    // Verifies that leaving the same session twice is handled safely.
    @Test
    void shouldHandleLeavingSameSessionTwice() {
        String code = "test-" + UUID.randomUUID();

        WebSocketSession session = mockSession("s1");

        sessionManager.join(code, "device1", session);

        sessionManager.leave(session);

        assertDoesNotThrow(() -> sessionManager.leave(session));

        assertEquals(0, sessionManager.getConnectedCount(code));
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
