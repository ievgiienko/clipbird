/**
 * Cryptographic operations using Web Crypto API.
 * All encryption/decryption happens client-side only.
 */

const PBKDF2_ITERATIONS = 600000;
const KEY_LENGTH = 256;
const IV_LENGTH = 12; // 96 bits for AES-GCM

/**
 * Derive an AES-GCM key from a password and salt using PBKDF2.
 */
export async function deriveKey(password, saltB64) {
    const encoder = new TextEncoder();
    const salt = base64ToBytes(saltB64);

    const keyMaterial = await crypto.subtle.importKey(
        'raw',
        encoder.encode(password),
        'PBKDF2',
        false,
        ['deriveKey']
    );

    return crypto.subtle.deriveKey(
        {
            name: 'PBKDF2',
            salt,
            iterations: PBKDF2_ITERATIONS,
            hash: 'SHA-256'
        },
        keyMaterial,
        { name: 'AES-GCM', length: KEY_LENGTH },
        false,
        ['encrypt', 'decrypt']
    );
}

/**
 * Compute a password hash for server-side verification.
 * This is NOT the encryption key — it's a separate derivation for auth only.
 */
export async function computePasswordHash(password, saltB64) {
    const encoder = new TextEncoder();
    const salt = base64ToBytes(saltB64);

    const keyMaterial = await crypto.subtle.importKey(
        'raw',
        encoder.encode(password),
        'PBKDF2',
        false,
        ['deriveBits']
    );

    const bits = await crypto.subtle.deriveBits(
        {
            name: 'PBKDF2',
            salt: new Uint8Array([...salt, 0x01]), // Different salt suffix for auth
            iterations: PBKDF2_ITERATIONS,
            hash: 'SHA-256'
        },
        keyMaterial,
        256
    );

    return bytesToBase64(new Uint8Array(bits));
}

/**
 * Encrypt text with AES-GCM.
 * Returns { ciphertext: base64, iv: base64 }
 */
export async function encryptText(key, plaintext) {
    const encoder = new TextEncoder();
    const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));

    const ciphertext = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv },
        key,
        encoder.encode(plaintext)
    );

    return {
        ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
        iv: bytesToBase64(iv)
    };
}

/**
 * Decrypt text with AES-GCM.
 */
export async function decryptText(key, ciphertextB64, ivB64) {
    const decoder = new TextDecoder();
    const ciphertext = base64ToBytes(ciphertextB64);
    const iv = base64ToBytes(ivB64);

    const plaintext = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv },
        key,
        ciphertext
    );

    return decoder.decode(plaintext);
}

/**
 * Encrypt a binary chunk with AES-GCM.
 * Returns { data: base64, iv: base64 }
 * @deprecated Use encryptChunkBinary for binary transport.
 */
export async function encryptChunk(key, chunk) {
    const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));

    const ciphertext = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv },
        key,
        chunk
    );

    return {
        data: bytesToBase64(new Uint8Array(ciphertext)),
        iv: bytesToBase64(iv)
    };
}

/**
 * Decrypt a binary chunk with AES-GCM.
 * @deprecated Use decryptChunkBinary for binary transport.
 */
export async function decryptChunk(key, dataB64, ivB64) {
    const data = base64ToBytes(dataB64);
    const iv = base64ToBytes(ivB64);

    return crypto.subtle.decrypt(
        { name: 'AES-GCM', iv },
        key,
        data
    );
}

/**
 * Encrypt a binary chunk with AES-GCM.
 * Returns { data: ArrayBuffer (encrypted bytes), iv: Uint8Array (raw 12 bytes) }
 * No base64 encoding — suitable for binary transport.
 */
export async function encryptChunkBinary(key, chunk) {
    const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));

    const ciphertext = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv },
        key,
        chunk
    );

    return { data: ciphertext, iv };
}

/**
 * Decrypt a binary chunk with AES-GCM using raw bytes.
 * @param {CryptoKey} key
 * @param {ArrayBuffer} data - raw encrypted bytes
 * @param {Uint8Array} iv - raw 12-byte IV
 * @returns {Promise<ArrayBuffer>}
 */
export async function decryptChunkBinary(key, data, iv) {
    return crypto.subtle.decrypt(
        { name: 'AES-GCM', iv },
        key,
        data
    );
}

/**
 * Encrypt a short string (e.g., file name) with AES-GCM.
 */
export async function encryptString(key, str) {
    return encryptText(key, str);
}

/**
 * Decrypt a short string.
 */
export async function decryptString(key, ciphertextB64, ivB64) {
    return decryptText(key, ciphertextB64, ivB64);
}

/**
 * Generate a random salt as base64.
 */
export function generateSalt() {
    const salt = crypto.getRandomValues(new Uint8Array(32));
    return bytesToBase64(salt);
}

/**
 * Compute SHA-256 hash of a File object.
 * Reads the file in chunks to avoid loading the entire file into memory at once.
 * Returns hex-encoded hash string.
 */
export async function computeFileHash(file) {
    const HASH_CHUNK_SIZE = 4 * 1024 * 1024; // 4 MB read chunks
    const totalSize = file.size;

    // For small files, hash in one shot
    if (totalSize <= HASH_CHUNK_SIZE) {
        const buffer = await file.arrayBuffer();
        const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
        return bufferToHex(hashBuffer);
    }

    // For large files, read in chunks — but Web Crypto digest() doesn't support
    // incremental updates, so we must read the whole file into memory.
    // We read chunk-by-chunk to avoid blocking the main thread too long.
    const chunks = [];
    let offset = 0;
    while (offset < totalSize) {
        const end = Math.min(offset + HASH_CHUNK_SIZE, totalSize);
        const blob = file.slice(offset, end);
        const buffer = await blob.arrayBuffer();
        chunks.push(new Uint8Array(buffer));
        offset = end;
    }

    // Concatenate all chunks
    const fullBuffer = new Uint8Array(totalSize);
    let pos = 0;
    for (const chunk of chunks) {
        fullBuffer.set(chunk, pos);
        pos += chunk.length;
    }

    const hashBuffer = await crypto.subtle.digest('SHA-256', fullBuffer);
    return bufferToHex(hashBuffer);
}

/**
 * Compute SHA-256 hash of a Blob (for verifying reconstructed files on target side).
 * Returns hex-encoded hash string.
 */
export async function computeBlobHash(blob) {
    const buffer = await blob.arrayBuffer();
    const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
    return bufferToHex(hashBuffer);
}

function bufferToHex(buffer) {
    const bytes = new Uint8Array(buffer);
    const hex = [];
    for (let i = 0; i < bytes.length; i++) {
        hex.push(bytes[i].toString(16).padStart(2, '0'));
    }
    return hex.join('');
}

// --- Base64 helpers ---

export function bytesToBase64(bytes) {
    let binary = '';
    for (let i = 0; i < bytes.length; i++) {
        binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
}

export function base64ToBytes(b64) {
    const binary = atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
}
