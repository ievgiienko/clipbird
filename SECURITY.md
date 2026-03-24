# Security Model

## What Is Encrypted

| Data | Encrypted? | Algorithm |
|------|-----------|-----------|
| Text content | Yes | AES-256-GCM |
| File content (chunks) | Yes | AES-256-GCM |
| File names | Yes | AES-256-GCM |
| File MIME types | Yes | AES-256-GCM |

## What the Server Can See

- Clipboard ID (code)
- Salt and KDF parameters
- Password hash (derived, not the actual password)
- Clipboard creation/expiration timestamps
- File sizes (in bytes)
- Number of chunks per file
- Source device ID for files
- Transfer metadata (source device, target device, chunk progress)
- Number of connected devices
- WebSocket connection events

## What the Server Cannot See

- User's password (never transmitted)
- Plaintext text content
- Plaintext file content
- File names (encrypted before storage)
- File MIME types (encrypted before storage)
- The encryption key (derived only in-browser)

## Key Derivation

### Encryption Key
```
Password + Salt ──► PBKDF2 (SHA-256, 600K iterations) ──► AES-256-GCM Key
```

### Authentication Hash
```
Password + (Salt ⊕ 0x01) ──► PBKDF2 (SHA-256, 600K iterations) ──► 256-bit Hash
```

The authentication hash uses a modified salt (appended byte `0x01`) to ensure the key used for encryption is cryptographically independent from the hash sent to the server for verification.

## Encryption Details

- **Algorithm**: AES-256-GCM (authenticated encryption)
- **Key derivation**: PBKDF2 with SHA-256, 600,000 iterations
- **IV/Nonce**: 96-bit random IV generated fresh for each encryption operation
- **Salt**: 256-bit random, generated server-side during clipboard creation

## Password Verification

The server stores a password hash derived from the user's password. This is NOT the same as the encryption key. The two are derived from different salts (the auth salt has an extra byte appended).

When a user opens an existing clipboard:
1. Client fetches the salt from the server (handshake endpoint)
2. Client derives the auth hash from the password and modified salt
3. Client sends the hash to the server for verification
4. If verified, client derives the encryption key from the password and original salt
5. Client decrypts all content locally

## Threat Model

### What this protects against
- Server compromise: encrypted data is useless without the password
- Network eavesdropping (with HTTPS): all data in transit is encrypted
- Server-side data breach: only ciphertext and metadata are stored

### What this does NOT protect against
- Weak passwords: users can choose poor passwords
- Client-side compromise: malware on the user's device
- Brute-force attacks on clipboard IDs (mitigated by rate limiting)
- Timing/metadata analysis: the server sees access patterns, file sizes, connection times
- Man-in-the-middle on HTTP (production MUST use HTTPS)
- Key derivation downgrade (parameters are fetched from server)

## MVP Security Limitations

1. **No rate limiting on password verification**: A determined attacker could brute-force passwords. Production should add rate limiting, account lockout, or proof-of-work challenges.

2. **Clipboard ID enumeration**: 8-character alphanumeric IDs have limited entropy. Production should add rate limiting per IP and consider longer IDs.

3. **No perfect forward secrecy**: If a password is compromised, all past and future data encrypted with that password is exposed.

4. **Server-provided salt**: The server generates the salt. A compromised server could provide a known salt to aid offline attacks. This is acceptable for MVP with HTTPS.

5. **No integrity verification of KDF parameters**: A compromised server could return reduced iteration counts. The client should validate minimums.

6. **Session management**: WebSocket sessions are not authenticated beyond initial join. Production should add session tokens.

## HTTPS Is Mandatory in Production

Without HTTPS:
- Passwords are transmitted in plaintext over WebSocket
- Password hashes can be intercepted
- Encrypted payloads could be tampered with (though AES-GCM provides authenticated encryption)
- The JavaScript itself could be modified in transit (complete compromise)

**Never deploy this application without HTTPS/WSS in production.**
