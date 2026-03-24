# Secure Online Clipboard

An end-to-end encrypted, real-time clipboard sharing system. Share text and files between devices securely — the server never sees your plaintext data.

## Features

- **End-to-end encryption**: All content is encrypted/decrypted in the browser using AES-GCM with PBKDF2-derived keys
- **Real-time sync**: Text changes propagate instantly to all connected devices via WebSocket
- **File transfer**: Chunked, encrypted peer-to-peer file relay through the server
- **Zero-knowledge server**: The server stores only ciphertext, salts, and metadata
- **Short clipboard IDs**: User-friendly 8-character alphanumeric codes
- **Auto-expiration**: Clipboards automatically expire after a configurable TTL
- **Multiple clipboards**: Support for many simultaneous clipboard sessions

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Java 25, Spring Boot 4.0 |
| Frontend | Plain JavaScript, HTML, CSS |
| Database | MySQL 8 |
| Real-time | WebSocket (native Spring) |
| Encryption | Web Crypto API (AES-GCM, PBKDF2) |
| Migrations | Flyway |
| Containerization | Docker, Docker Compose |

## Quick Start with Docker Compose

```bash
# Clone and enter the project
cd secure-clipboard

# Copy environment file
cp .env.example .env

# Build and start
docker compose up --build -d

# Open in browser
# http://localhost:8080
```

## Local Development (without Docker)

### Prerequisites
- Java 25+ (JDK)
- Maven 3.9+
- MySQL 8 running locally

### Setup

```bash
# Create MySQL database
mysql -u root -p -e "CREATE DATABASE secure_clipboard; CREATE USER 'clipboard_user'@'localhost' IDENTIFIED BY 'clipboard_pass'; GRANT ALL ON secure_clipboard.* TO 'clipboard_user'@'localhost';"

# Build
mvn clean package

# Run
java -jar target/secure-clipboard-1.0.0.jar
```

The application starts at `http://localhost:8080`.

## Project Structure

```
secure-clipboard/
├── src/main/java/com/secureclipboard/
│   ├── config/          # Spring configuration (WebSocket, CORS, properties)
│   ├── controller/      # REST API controllers
│   ├── domain/          # JPA entities and enums
│   ├── dto/             # Request/response DTOs
│   ├── repository/      # Spring Data JPA repositories
│   ├── service/         # Business logic services
│   ├── util/            # Utility classes (code generation)
│   └── websocket/       # WebSocket message handler
├── src/main/resources/
│   ├── static/          # Frontend (HTML, CSS, JS)
│   │   ├── css/
│   │   ├── js/          # Modular JS (crypto, api, ws, state, ui, app)
│   │   └── index.html
│   ├── db/migration/    # Flyway SQL migrations
│   └── application.yml  # Configuration
├── src/test/            # Tests
├── Dockerfile
├── docker-compose.yml
├── .env.example
└── pom.xml
```

## API Summary

| Method | Endpoint | Description |
|--------|---------|-------------|
| POST | `/api/clipboards` | Create new clipboard |
| POST | `/api/clipboards/{code}/password` | Set clipboard password |
| GET | `/api/clipboards/{code}/handshake` | Get salt and KDF params |
| POST | `/api/clipboards/{code}/verify` | Verify password hash |
| GET | `/api/clipboards/{code}/state` | Get encrypted clipboard state |
| DELETE | `/api/clipboards/{code}` | Delete clipboard |
| POST | `/api/clipboards/{code}/files` | Register file manifest |
| DELETE | `/api/clipboards/{code}/files/{fileCode}` | Remove file |
| POST | `/api/transfers` | Create file transfer |
| PUT | `/api/transfers/{id}/chunks/{n}` | Upload encrypted chunk |
| GET | `/api/transfers/{id}/chunks/{n}` | Download encrypted chunk |
| POST | `/api/transfers/{id}/ack` | Acknowledge chunk receipt |
| DELETE | `/api/transfers/{id}` | Cancel transfer |
| WS | `/ws/clipboard` | Real-time WebSocket endpoint |

## Encryption Model

1. User provides a password
2. Server generates a random salt and returns it to the client
3. Client derives an AES-256-GCM key using PBKDF2 (600,000 iterations, SHA-256)
4. A separate password hash is derived (with a modified salt) for server-side authentication
5. All text and file content is encrypted client-side before transmission
6. File names and MIME types are also encrypted
7. The server only stores ciphertext, IVs, salts, and sync metadata

## Known MVP Limitations

- Source device must remain online for file downloads to work
- File relay is temporary — not permanent storage
- Password-based only; no password recovery mechanism
- If the wrong password is entered, decryption simply fails silently
- Server can see some metadata: clipboard IDs, file sizes, chunk counts, timestamps
- Clipboard ID brute-force protection is basic (should be strengthened for production)
- **Production deployment requires HTTPS** for security (WSS for WebSocket)
- Large file support depends on browser memory and IndexedDB capacity
- No user accounts or persistent sessions
- Single-server only (no horizontal scaling without Redis)

## Configuration

All settings are configurable via environment variables. See `.env.example` for defaults.

| Variable | Default | Description |
|----------|---------|-------------|
| `CLIPBOARD_TTL_HOURS` | 24 | Clipboard expiration time |
| `CHUNK_SIZE` | 5242880 | File chunk size in bytes (5 MB) |
| `MAX_TEXT_SIZE` | 10485760 | Max text content size (10 MB) |
| `MAX_FILE_SIZE` | 524288000 | Max file size (500 MB) |
| `CLIPBOARD_ID_LENGTH` | 8 | Length of generated clipboard codes |

## License

MIT
