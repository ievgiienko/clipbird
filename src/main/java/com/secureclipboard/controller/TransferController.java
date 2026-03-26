package com.secureclipboard.controller;

import com.secureclipboard.dto.*;
import com.secureclipboard.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(
            @Valid @RequestBody CreateTransferRequest request) {
        TransferResponse response = transferService.createTransfer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{transferCode}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable String transferCode) {
        TransferResponse response = transferService.getTransfer(transferCode);
        return ResponseEntity.ok(response);
    }

    /**
     * Binary chunk upload via multipart/form-data.
     * - "data" part: raw encrypted bytes (application/octet-stream)
     * - "iv" part: IV string
     */
    @PutMapping(value = "/{transferCode}/chunks/{chunkNo}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> uploadChunk(@PathVariable String transferCode,
                                             @PathVariable int chunkNo,
                                             @RequestPart("data") MultipartFile data,
                                             @RequestPart("iv") String iv,
                                             @RequestPart(value = "hash", required = false) String hash) throws IOException {
        transferService.uploadChunk(transferCode, chunkNo, data.getBytes(), iv.trim(),
            hash != null ? hash.trim() : null);
        return ResponseEntity.ok().build();
    }

    /**
     * Binary chunk download.
     * Returns raw encrypted bytes as application/octet-stream.
     * IV is in the X-Chunk-IV response header.
     * Last-chunk indicator is in the X-Last-Chunk header.
     */
    @GetMapping(value = "/{transferCode}/chunks/{chunkNo}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> downloadChunk(@PathVariable String transferCode,
                                                 @PathVariable int chunkNo) {
        ChunkDownloadResponse chunk = transferService.downloadChunk(transferCode, chunkNo);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Chunk-IV", chunk.iv());
        headers.set("X-Last-Chunk", String.valueOf(chunk.lastChunk()));
        headers.set("X-Chunk-No", String.valueOf(chunk.chunkNo()));
        if (chunk.chunkHash() != null) {
            headers.set("X-Chunk-Hash", chunk.chunkHash());
        }
        return ResponseEntity.ok().headers(headers).body(chunk.data());
    }

    @PostMapping("/{transferCode}/ack")
    public ResponseEntity<TransferResponse> acknowledgeChunk(@PathVariable String transferCode) {
        TransferResponse response = transferService.acknowledgeChunk(transferCode);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{transferCode}")
    public ResponseEntity<Void> cancelTransfer(@PathVariable String transferCode) {
        transferService.cancelTransfer(transferCode);
        return ResponseEntity.noContent().build();
    }
}
