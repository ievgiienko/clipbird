package com.secureclipboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.secureclipboard.domain.*;
import com.secureclipboard.repository.ClipboardFileRepository;
import com.secureclipboard.repository.ClipboardRepository;
import com.secureclipboard.service.ClipboardService;
import com.secureclipboard.service.DeviceSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests binary chunk upload/download via the REST API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClipboardService clipboardService;

    @Autowired
    private ClipboardRepository clipboardRepository;

    @Autowired
    private ClipboardFileRepository fileRepository;

    @Autowired
    private DeviceSessionManager sessionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String clipboardCode;
    private String fileCode;

    @BeforeEach
    void setUp() {
        var created = clipboardService.createClipboard(null, null);
        clipboardCode = created.clipboardCode();
        clipboardService.setPasswordHash(clipboardCode, "testHash");

        WebSocketSession mockSession = mock(WebSocketSession.class);
        when(mockSession.getId()).thenReturn("session-src-" + java.util.UUID.randomUUID());
        when(mockSession.isOpen()).thenReturn(true);
        sessionManager.join(clipboardCode, "source-dev", mockSession);

        Clipboard clipboard = clipboardRepository.findByClipboardCode(clipboardCode).orElseThrow();
        fileCode = java.util.UUID.randomUUID().toString();
        ClipboardFile file = new ClipboardFile();
        file.setClipboard(clipboard);
        file.setFileCode(fileCode);
        file.setEncryptedName("enc");
        file.setEncryptedNameIv("iv");
        file.setSizeBytes(10_000_000);
        file.setChunkSize(5_242_880);
        file.setTotalChunks(2);
        file.setSourceDeviceId("source-dev");
        file.setStatus(FileStatus.AVAILABLE);
        fileRepository.save(file);
    }

    @Test
    void shouldCreateTransferWithChunkSizeInfo() throws Exception {
        String body = objectMapper.writeValueAsString(
            new java.util.HashMap<>() {{
                put("clipboardCode", clipboardCode);
                put("fileCode", fileCode);
                put("targetDeviceId", "target-dev");
            }});

        mockMvc.perform(post("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.transferCode").exists())
            .andExpect(jsonPath("$.defaultChunkSize").exists())
            .andExpect(jsonPath("$.minChunkSize").exists())
            .andExpect(jsonPath("$.maxChunkSize").exists());
    }

    @Test
    void shouldUploadBinaryChunkAndDownloadAsOctetStream() throws Exception {
        // Create transfer
        String body = objectMapper.writeValueAsString(
            new java.util.HashMap<>() {{
                put("clipboardCode", clipboardCode);
                put("fileCode", fileCode);
                put("targetDeviceId", "target-dev");
            }});

        MvcResult createResult = mockMvc.perform(post("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode transfer = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String transferCode = transfer.get("transferCode").asText();

        // Upload binary chunk via multipart
        byte[] chunkData = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
        MockMultipartFile dataPart = new MockMultipartFile("data", "chunk", "application/octet-stream", chunkData);
        MockMultipartFile ivPart = new MockMultipartFile("iv", "", "text/plain", "test-iv-value".getBytes());

        mockMvc.perform(multipart("/api/transfers/{tc}/chunks/{cn}", transferCode, 0)
                .file(dataPart)
                .file(ivPart)
                .with(request -> { request.setMethod("PUT"); return request; }))
            .andExpect(status().isOk());

        // Download binary chunk
        MvcResult downloadResult = mockMvc.perform(get("/api/transfers/{tc}/chunks/{cn}", transferCode, 0))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Chunk-IV", "test-iv-value"))
            .andExpect(header().string("X-Last-Chunk", "false"))
            .andExpect(header().string("X-Chunk-No", "0"))
            .andReturn();

        byte[] downloadedData = downloadResult.getResponse().getContentAsByteArray();
        assertArrayEquals(chunkData, downloadedData);
    }

    @Test
    void shouldDownloadLastChunkWithCorrectHeader() throws Exception {
        // Create transfer for a file with 2 chunks
        String body = objectMapper.writeValueAsString(
            new java.util.HashMap<>() {{
                put("clipboardCode", clipboardCode);
                put("fileCode", fileCode);
                put("targetDeviceId", "target-dev");
            }});

        MvcResult createResult = mockMvc.perform(post("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode transfer = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String transferCode = transfer.get("transferCode").asText();

        // Upload and ack chunk 0
        MockMultipartFile dataPart0 = new MockMultipartFile("data", "chunk", "application/octet-stream", new byte[]{1});
        MockMultipartFile ivPart0 = new MockMultipartFile("iv", "", "text/plain", "iv0".getBytes());
        mockMvc.perform(multipart("/api/transfers/{tc}/chunks/{cn}", transferCode, 0)
                .file(dataPart0).file(ivPart0)
                .with(r -> { r.setMethod("PUT"); return r; }))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/transfers/{tc}/ack", transferCode))
            .andExpect(status().isOk());

        // Upload chunk 1 (last chunk)
        MockMultipartFile dataPart1 = new MockMultipartFile("data", "chunk", "application/octet-stream", new byte[]{2});
        MockMultipartFile ivPart1 = new MockMultipartFile("iv", "", "text/plain", "iv1".getBytes());
        mockMvc.perform(multipart("/api/transfers/{tc}/chunks/{cn}", transferCode, 1)
                .file(dataPart1).file(ivPart1)
                .with(r -> { r.setMethod("PUT"); return r; }))
            .andExpect(status().isOk());

        // Download last chunk
        mockMvc.perform(get("/api/transfers/{tc}/chunks/{cn}", transferCode, 1))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Last-Chunk", "true"));
    }
}
