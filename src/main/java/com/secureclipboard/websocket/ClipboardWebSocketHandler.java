package com.secureclipboard.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.secureclipboard.dto.TextSnapshotDto;
import com.secureclipboard.dto.TransferResponse;
import com.secureclipboard.service.ClipboardService;
import com.secureclipboard.service.DeviceSessionManager;
import com.secureclipboard.service.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

@Component
public class ClipboardWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ClipboardWebSocketHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DeviceSessionManager sessionManager;
    private final ClipboardService clipboardService;
    private final TransferService transferService;

    public ClipboardWebSocketHandler(DeviceSessionManager sessionManager,
                                      ClipboardService clipboardService,
                                      TransferService transferService) {
        this.sessionManager = sessionManager;
        this.clipboardService = clipboardService;
        this.transferService = transferService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("WebSocket connection established: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            sendError(session, "Invalid JSON");
            return;
        }

        String type = payload.path("type").asText("");
        try {
            switch (type) {
                case "join" -> handleJoin(session, payload);
                case "leave" -> handleLeave(session);
                case "text_update" -> handleTextUpdate(session, payload);
                case "file_added" -> handleFileAdded(session, payload);
                case "file_removed" -> handleFileRemoved(session, payload);
                case "delete_clipboard" -> handleDeleteClipboard(session, payload);
                case "transfer_request" -> handleTransferRequest(session, payload);
                case "chunk_uploaded" -> handleChunkUploaded(session, payload);
                case "chunk_ack" -> handleChunkAck(session, payload);
                case "transfer_cancel" -> handleTransferCancel(session, payload);
                case "ping" -> sendMessage(session, createMessage("pong", null));
                default -> sendError(session, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            log.error("Error handling message type: {}", type, e);
            sendError(session, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String deviceId = sessionManager.getDeviceId(session);
        String clipboardCode = sessionManager.getClipboardCode(session);
        sessionManager.leave(session);

        if (clipboardCode != null && deviceId != null) {
            broadcastToRoom(clipboardCode, createDeviceEvent("device_left", deviceId, clipboardCode), session);
        }
    }

    private void handleJoin(WebSocketSession session, JsonNode payload) throws IOException {
        String clipboardCode = payload.path("clipboardCode").asText();
        String deviceId = payload.path("deviceId").asText();
        String passwordHash = payload.path("passwordHash").asText();

        if (clipboardCode.isEmpty() || deviceId.isEmpty() || passwordHash.isEmpty()) {
            sendError(session, "Missing clipboardCode, deviceId, or passwordHash");
            return;
        }

        if (!clipboardService.verifyPassword(clipboardCode, passwordHash)) {
            sendError(session, "Invalid password");
            ObjectNode errorMsg = objectMapper.createObjectNode();
            errorMsg.put("type", "auth_failed");
            errorMsg.put("message", "Invalid password");
            sendMessage(session, errorMsg.toString());
            return;
        }

        sessionManager.join(clipboardCode, deviceId, session);

        ObjectNode joinConfirm = objectMapper.createObjectNode();
        joinConfirm.put("type", "joined");
        joinConfirm.put("clipboardCode", clipboardCode);
        joinConfirm.put("deviceId", deviceId);
        joinConfirm.put("connectedDevices", sessionManager.getConnectedCount(clipboardCode));
        sendMessage(session, joinConfirm.toString());

        broadcastToRoom(clipboardCode, createDeviceEvent("device_joined", deviceId, clipboardCode), session);
    }

    private void handleLeave(WebSocketSession session) {
        String deviceId = sessionManager.getDeviceId(session);
        String clipboardCode = sessionManager.getClipboardCode(session);
        sessionManager.leave(session);

        if (clipboardCode != null && deviceId != null) {
            broadcastToRoom(clipboardCode, createDeviceEvent("device_left", deviceId, clipboardCode), null);
        }
    }

    private void handleTextUpdate(WebSocketSession session, JsonNode payload) throws IOException {
        String clipboardCode = sessionManager.getClipboardCode(session);
        String deviceId = sessionManager.getDeviceId(session);
        if (clipboardCode == null) {
            sendError(session, "Not joined to any clipboard");
            return;
        }

        String ciphertextB64 = payload.path("ciphertext").asText();
        String iv = payload.path("iv").asText();
        long expectedVersion = payload.path("version").asLong(-1);

        byte[] ciphertext = Base64.getDecoder().decode(ciphertextB64);
        TextSnapshotDto updated = clipboardService.updateText(clipboardCode, ciphertext, iv, expectedVersion, deviceId);

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "text_updated");
        msg.put("ciphertext", updated.ciphertext());
        msg.put("iv", updated.iv());
        msg.put("version", updated.version());
        msg.put("updatedBy", deviceId);
        broadcastToRoom(clipboardCode, msg.toString(), null);
    }

    private void handleFileAdded(WebSocketSession session, JsonNode payload) {
        String clipboardCode = sessionManager.getClipboardCode(session);
        if (clipboardCode == null) return;

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "file_added");
        msg.set("file", payload.path("file"));
        broadcastToRoom(clipboardCode, msg.toString(), session);
    }

    private void handleFileRemoved(WebSocketSession session, JsonNode payload) {
        String clipboardCode = sessionManager.getClipboardCode(session);
        if (clipboardCode == null) return;

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "file_removed");
        msg.put("fileCode", payload.path("fileCode").asText());
        broadcastToRoom(clipboardCode, msg.toString(), session);
    }

    private void handleDeleteClipboard(WebSocketSession session, JsonNode payload) {
        String clipboardCode = sessionManager.getClipboardCode(session);
        if (clipboardCode == null) return;

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "clipboard_deleted");
        msg.put("clipboardCode", clipboardCode);
        broadcastToRoom(clipboardCode, msg.toString(), null);

        clipboardService.deleteClipboard(clipboardCode);
    }

    /**
     * Target device requests a chunk from the source.
     * Includes chunkSize so the source knows how to slice the file.
     */
    private void handleTransferRequest(WebSocketSession session, JsonNode payload) throws IOException {
        String clipboardCode = sessionManager.getClipboardCode(session);
        if (clipboardCode == null) return;

        String transferCode = payload.path("transferCode").asText();
        String fileCode = payload.path("fileCode").asText();
        String sourceDeviceId = payload.path("sourceDeviceId").asText();
        int chunkNo = payload.path("chunkNo").asInt(0);
        int chunkSize = payload.path("chunkSize").asInt(0);

        WebSocketSession sourceSession = sessionManager.getSession(clipboardCode, sourceDeviceId);
        if (sourceSession != null && sourceSession.isOpen()) {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("type", "send_chunk");
            msg.put("transferCode", transferCode);
            msg.put("fileCode", fileCode);
            msg.put("chunkNo", chunkNo);
            if (chunkSize > 0) {
                msg.put("chunkSize", chunkSize);
            }
            sendMessage(sourceSession, msg.toString());
        } else {
            sendError(session, "Source device is offline");
        }
    }

    /**
     * Source device signals that a chunk has been uploaded to the server.
     * Notify the target device.
     */
    private void handleChunkUploaded(WebSocketSession session, JsonNode payload) throws IOException {
        String transferCode = payload.path("transferCode").asText();
        String targetDeviceId = payload.path("targetDeviceId").asText();
        String clipboardCode = sessionManager.getClipboardCode(session);
        if (clipboardCode == null) return;

        WebSocketSession targetSession = sessionManager.getSession(clipboardCode, targetDeviceId);
        if (targetSession != null && targetSession.isOpen()) {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("type", "chunk_ready");
            msg.put("transferCode", transferCode);
            msg.put("chunkNo", payload.path("chunkNo").asInt());
            sendMessage(targetSession, msg.toString());
        }
    }

    /**
     * Target device acknowledges a downloaded chunk.
     * The server clears the buffer slot and determines next actions:
     * - If transfer completed, notify both devices.
     * - If more chunks needed, request the next one from source.
     */
    private void handleChunkAck(WebSocketSession session, JsonNode payload) throws IOException {
        String transferCode = payload.path("transferCode").asText();
        int chunkSize = payload.path("chunkSize").asInt(0);
        TransferResponse transferResp = transferService.acknowledgeChunk(transferCode);

        String clipboardCode = sessionManager.getClipboardCode(session);
        if (clipboardCode == null) return;

        if ("COMPLETED".equals(transferResp.status())) {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("type", "transfer_complete");
            msg.put("transferCode", transferCode);
            msg.put("fileCode", transferResp.fileCode());
            broadcastToDevices(clipboardCode, msg.toString(),
                transferResp.sourceDeviceId(), transferResp.targetDeviceId());
        } else {
            // Request the next chunk from the source
            int nextChunk = transferResp.currentChunkNo();
            if (nextChunk < transferResp.totalChunks()) {
                WebSocketSession sourceSession = sessionManager.getSession(clipboardCode, transferResp.sourceDeviceId());
                if (sourceSession != null && sourceSession.isOpen()) {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("type", "send_chunk");
                    msg.put("transferCode", transferCode);
                    msg.put("fileCode", transferResp.fileCode());
                    msg.put("chunkNo", nextChunk);
                    if (chunkSize > 0) {
                        msg.put("chunkSize", chunkSize);
                    }
                    sendMessage(sourceSession, msg.toString());
                }
            }
        }
    }

    private void handleTransferCancel(WebSocketSession session, JsonNode payload) {
        String transferCode = payload.path("transferCode").asText();
        transferService.cancelTransfer(transferCode);

        String clipboardCode = sessionManager.getClipboardCode(session);
        if (clipboardCode == null) return;

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "transfer_cancelled");
        msg.put("transferCode", transferCode);
        broadcastToRoom(clipboardCode, msg.toString(), null);
    }

    // --- Broadcast helpers ---

    private void broadcastToRoom(String clipboardCode, String message, WebSocketSession exclude) {
        Map<String, WebSocketSession> room = sessionManager.getRoom(clipboardCode);
        for (var entry : room.entrySet()) {
            WebSocketSession s = entry.getValue();
            if (s.isOpen() && (exclude == null || !s.getId().equals(exclude.getId()))) {
                try {
                    s.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("Failed to send message to device {}", entry.getKey(), e);
                }
            }
        }
    }

    private void broadcastToDevices(String clipboardCode, String message, String... deviceIds) {
        for (String deviceId : deviceIds) {
            WebSocketSession s = sessionManager.getSession(clipboardCode, deviceId);
            if (s != null && s.isOpen()) {
                try {
                    s.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("Failed to send message to device {}", deviceId, e);
                }
            }
        }
    }

    private void sendMessage(WebSocketSession session, String message) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(message));
        }
    }

    private void sendError(WebSocketSession session, String errorMessage) throws IOException {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "error");
        msg.put("message", errorMessage);
        sendMessage(session, msg.toString());
    }

    private String createMessage(String type, ObjectNode data) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", type);
        if (data != null) msg.setAll(data);
        return msg.toString();
    }

    private String createDeviceEvent(String type, String deviceId, String clipboardCode) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", type);
        msg.put("deviceId", deviceId);
        msg.put("connectedDevices", sessionManager.getConnectedCount(clipboardCode));
        return msg.toString();
    }
}
