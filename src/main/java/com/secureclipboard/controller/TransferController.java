package com.secureclipboard.controller;

import com.secureclipboard.dto.*;
import com.secureclipboard.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.Map;

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

    @PutMapping("/{transferCode}/chunks/{chunkNo}")
    public ResponseEntity<Void> uploadChunk(@PathVariable String transferCode,
                                             @PathVariable int chunkNo,
                                             @RequestBody Map<String, String> body) {
        byte[] data = Base64.getDecoder().decode(body.get("data"));
        String iv = body.get("iv");
        transferService.uploadChunk(transferCode, chunkNo, data, iv);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{transferCode}/chunks/{chunkNo}")
    public ResponseEntity<ChunkDownloadResponse> downloadChunk(@PathVariable String transferCode,
                                                                @PathVariable int chunkNo) {
        ChunkDownloadResponse response = transferService.downloadChunk(transferCode, chunkNo);
        return ResponseEntity.ok(response);
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
