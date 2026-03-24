# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Set JAVA_HOME to JDK 25+ (project targets Java 25)
export JAVA_HOME="C:/Program Files/Java/jdk-26"

# Build (skip tests)
mvn clean package -DskipTests

# Run all tests (uses H2 in-memory DB, no MySQL needed)
mvn test

# Run a single test class
mvn test -Dtest=ClipboardServiceTest

# Run a single test method
mvn test -Dtest=ClipboardServiceTest#shouldCreateClipboard

# Compile only (no tests, no package)
mvn compile

# Start with Docker Compose (MySQL + app)
bash init-env.sh   # generates .env with random DB passwords
docker compose up --build -d
```

## Architecture

This is an end-to-end encrypted clipboard sharing system. **The server never sees plaintext** — all encryption/decryption happens in the browser via Web Crypto API (AES-256-GCM, PBKDF2 key derivation with 600K iterations).

### Request Flow

REST API handles CRUD operations (clipboard creation, state retrieval, file manifests, transfer coordination). WebSocket (`/ws/clipboard`) handles all real-time events: text sync, file notifications, transfer signaling, presence.

### Two-Phase Clipboard Creation

Clipboard creation is intentionally two-phase to solve a salt coordination problem:
1. `POST /api/clipboards` — server generates salt, returns it (no password yet)
2. `POST /api/clipboards/{code}/password` — client computes password hash using server salt, sends it

This ensures both the creating device and any joining device derive the same hash from the same salt.

### File Transfer Model

Files are never fully uploaded to the server. The server acts as a single-chunk relay:
1. Target device requests download → server signals source device via WebSocket
2. Source reads chunk from IndexedDB, encrypts it, uploads to server relay endpoint
3. Target downloads the encrypted chunk, decrypts locally, sends ACK
4. Server deletes temp chunk, requests next one from source

Only one encrypted chunk is held server-side at any time per transfer.

### Session Management

`DeviceSessionManager` tracks WebSocket sessions in `ConcurrentHashMap` (clipboardCode → deviceId → session). This is MVP-only; horizontal scaling requires replacing with Redis pub/sub.

### Cleanup

`CleanupService` runs three scheduled tasks:
1. **Expire active clipboards** past TTL — sets status to EXPIRED, removes child records (text snapshots, files, transfers), closes WebSocket sessions.
2. **Fail abandoned transfers** — marks expired in-progress transfers as FAILED, clears temp chunk data.
3. **Hard-delete old clipboards** — permanently removes DELETED/EXPIRED clipboard rows (and any remaining child records) after a configurable retention period (`app.cleanup.hard-delete-delay-hours`, default 168h / 7 days). This frees clipboard codes for reuse. Can be disabled via `app.cleanup.hard-delete-enabled: false`.

## Key Conventions

- **DTOs**: Java records in `dto/` package. All API input uses `@Valid` with Jakarta validation.
- **Entities**: Standard JPA with `@PrePersist` lifecycle hooks. Clipboard deletion sets status to DELETED and explicitly clears the text snapshot reference before flushing to avoid Hibernate cascade issues.
- **WebSocket protocol**: JSON messages with a `type` field for routing. Handler switches on type string in `ClipboardWebSocketHandler`.
- **Frontend**: Vanilla ES modules (no build step). `app.js` orchestrates; `crypto.js` handles all Web Crypto API calls; `router.js` handles SPA routing; `qr.js` generates QR codes.
- **SPA Routing**: `SpaController` forwards `/create`, `/open`, `/c/{code}` to `index.html`. Client-side router (`router.js`) uses History API to show the correct page. Links with `data-link` attribute are intercepted by the router.
- **Config**: All app-specific settings are under `app.*` prefix in `application.yml`, bound via `AppProperties` (@ConfigurationProperties). All configurable via environment variables.

## Test Setup

Tests use H2 in-memory database with `spring.jpa.hibernate.ddl-auto: create-drop` (Flyway disabled). Test config is in `src/test/resources/application.yml`. Spring Boot 4 requires `spring-boot-webmvc-test` as a separate dependency for `@AutoConfigureMockMvc` (import from `org.springframework.boot.webmvc.test.autoconfigure`).
