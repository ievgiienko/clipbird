# Architecture

## System Overview

```
┌─────────────┐     HTTPS/WSS     ┌─────────────────┐     JDBC     ┌─────────┐
│   Browser    │◄──────────────────►│   Spring Boot   │◄────────────►│  MySQL  │
│  (Device A)  │   REST + WS       │   Application   │              │   DB    │
└─────────────┘                    └─────────────────┘              └─────────┘
                                          ▲
┌─────────────┐     HTTPS/WSS            │
│   Browser    │◄────────────────────────┘
│  (Device B)  │
└─────────────┘
```

All encryption and decryption occurs exclusively in the browser. The server acts as an encrypted relay and synchronization hub.

## Backend Architecture

### Package Structure

- **config**: Spring configuration beans (WebSocket, CORS, application properties)
- **controller**: REST API endpoints (ClipboardController, TransferController)
- **domain**: JPA entities (Clipboard, ClipboardTextSnapshot, ClipboardFile, ChunkTransfer) and enums
- **dto**: Immutable request/response records
- **repository**: Spring Data JPA interfaces
- **service**: Core business logic
  - `ClipboardService`: clipboard CRUD, text updates, file management
  - `TransferService`: file transfer coordination
  - `DeviceSessionManager`: in-memory WebSocket session tracking
  - `CleanupService`: scheduled cleanup of expired data
- **websocket**: `ClipboardWebSocketHandler` — handles all real-time messaging
- **util**: `CodeGenerator` — generates clipboard codes and salts

### Session Management

`DeviceSessionManager` maintains in-memory maps:
- `rooms`: clipboardCode → {deviceId → WebSocketSession}
- `sessionToDevice`: sessionId → deviceId
- `sessionToClipboard`: sessionId → clipboardCode

This is MVP-suitable for single-instance deployments. For horizontal scaling, replace with Redis pub/sub.

## Frontend Architecture

### Module Structure

```
js/
├── crypto.js      # Web Crypto API: key derivation, encrypt/decrypt
├── api.js         # REST API client (fetch-based)
├── ws.js          # WebSocket client with auto-reconnect
├── file-store.js  # IndexedDB local file storage
├── state.js       # Centralized application state
├── ui.js          # DOM manipulation helpers
└── app.js         # Main orchestrator (event handlers, flow control)
```

All modules use ES module imports. No build step required.

## Data Flow: Text Update

```
Device A                    Server                     Device B
   │                          │                           │
   │ 1. User types text       │                           │
   │ 2. Debounce (500ms)      │                           │
   │ 3. Encrypt (AES-GCM)     │                           │
   │ 4. WS: text_update ──────►│                           │
   │                          │ 5. Store ciphertext        │
   │                          │ 6. Increment version       │
   │                          │ 7. WS: text_updated ──────►│
   │                          │                           │ 8. Decrypt (AES-GCM)
   │◄────── WS: text_updated  │                           │ 9. Display plaintext
   │ 10. Update version       │                           │
```

Version numbers are incremented server-side. The system uses last-write-wins conflict resolution.

## Data Flow: File Transfer

```
Device A (source)           Server                     Device B (target)
   │                          │                           │
   │ 1. Select file           │                           │
   │ 2. Store in IndexedDB    │                           │
   │ 3. Encrypt metadata      │                           │
   │ 4. POST file manifest ──►│ 5. Store manifest         │
   │ 5. WS: file_added ──────►│──── WS: file_added ─────►│ 6. Show in UI
   │                          │                           │
   │                          │◄── POST /transfers ───────│ 7. Click Download
   │                          │ 8. Create transfer         │
   │◄── WS: send_chunk ──────│                           │
   │                          │                           │
   │ 9. Read chunk from IDB   │                           │
   │ 10. Encrypt chunk        │                           │
   │ 11. PUT chunk ──────────►│ 12. Store temp chunk      │
   │ 12. WS: chunk_uploaded──►│── WS: chunk_ready ──────►│
   │                          │                           │ 13. GET chunk
   │                          │◄────────────────────────── │ 14. Decrypt chunk
   │                          │                           │ 15. Append to buffer
   │                          │◄── WS: chunk_ack ────────│
   │                          │ 16. Delete temp chunk     │
   │                          │ 17. If more chunks:       │
   │◄── WS: send_chunk ──────│     goto step 9           │
   │         ...              │         ...               │
   │                          │── WS: transfer_complete──►│ 18. Save file via
   │                          │                           │     browser download
```

Key design: the server holds only ONE encrypted chunk at a time per transfer.

## Data Flow: Clipboard Deletion

```
Device A                    Server                     Device B
   │                          │                           │
   │ WS: delete_clipboard ───►│                           │
   │                          │ 1. Delete transfers       │
   │                          │ 2. Delete files           │
   │                          │ 3. Delete text snapshot   │
   │                          │ 4. Mark DELETED           │
   │                          │ 5. Remove WS room         │
   │                          │                           │
   │◄─ WS: clipboard_deleted │── WS: clipboard_deleted──►│
   │                          │                           │
   │ 6. Show overlay          │                           │ 7. Show overlay
   │ 7. Clear local state     │                           │ 8. Clear local state
```

## Cleanup Strategy

Two scheduled tasks run periodically (default: every 5 minutes):

1. **Expired Clipboards**: Finds active clipboards past their `expires_at` timestamp, deletes all related data, marks as EXPIRED
2. **Expired Transfers**: Finds active transfers past their timeout, marks as FAILED, clears temp chunk data

## Database Schema

```
clipboards ──────────── clipboard_text_snapshots (1:1)
     │
     ├──────────── clipboard_files (1:N)
     │                    │
     └──────────── chunk_transfers (1:N) ──── clipboard_files (N:1)
```

All tables use InnoDB with `ON DELETE CASCADE` from clipboards, ensuring clean deletion.
