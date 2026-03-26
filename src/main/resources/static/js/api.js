/**
 * REST API client for the Secure Clipboard backend.
 */

const BASE_URL = '/api';

async function request(method, path, body = null) {
    const opts = {
        method,
        headers: { 'Content-Type': 'application/json' }
    };
    if (body) opts.body = JSON.stringify(body);

    const res = await fetch(`${BASE_URL}${path}`, opts);

    if (!res.ok) {
        const err = await res.json().catch(() => ({ message: res.statusText }));
        throw new Error(err.message || `HTTP ${res.status}`);
    }

    if (res.status === 204) return null;
    const text = await res.text();
    return text ? JSON.parse(text) : null;
}

export async function createClipboard() {
    return request('POST', '/clipboards');
}

export async function setPassword(clipboardCode, passwordHash) {
    return request('POST', `/clipboards/${clipboardCode}/password`, { passwordHash });
}

export async function getHandshake(clipboardCode) {
    return request('GET', `/clipboards/${clipboardCode}/handshake`);
}

export async function verifyPassword(clipboardCode, passwordHash) {
    try {
        await request('POST', `/clipboards/${clipboardCode}/verify`, { passwordHash });
        return true;
    } catch {
        return false;
    }
}

export async function getClipboardState(clipboardCode) {
    return request('GET', `/clipboards/${clipboardCode}/state`);
}

export async function deleteClipboard(clipboardCode) {
    return request('DELETE', `/clipboards/${clipboardCode}`);
}

export async function addFile(clipboardCode, fileManifest) {
    return request('POST', `/clipboards/${clipboardCode}/files`, fileManifest);
}

export async function removeFile(clipboardCode, fileCode) {
    return request('DELETE', `/clipboards/${clipboardCode}/files/${fileCode}`);
}

export async function createTransfer(clipboardCode, fileCode, targetDeviceId) {
    return request('POST', '/transfers', { clipboardCode, fileCode, targetDeviceId });
}

export async function getTransfer(transferCode) {
    return request('GET', `/transfers/${transferCode}`);
}

/**
 * Upload an encrypted chunk as binary via multipart/form-data.
 * @param {string} transferCode
 * @param {number} chunkNo
 * @param {ArrayBuffer} data - raw encrypted bytes
 * @param {string} iv - base64-encoded IV string
 * @param {string} hash - hex-encoded SHA-256 hash of the plaintext chunk
 */
export async function uploadChunk(transferCode, chunkNo, data, iv, hash) {
    const formData = new FormData();
    formData.append('data', new Blob([data], { type: 'application/octet-stream' }), 'chunk');
    formData.append('iv', iv);
    formData.append('hash', hash);

    const res = await fetch(`${BASE_URL}/transfers/${transferCode}/chunks/${chunkNo}`, {
        method: 'PUT',
        body: formData
    });

    if (!res.ok) {
        const err = await res.json().catch(() => ({ message: res.statusText }));
        throw new Error(err.message || `HTTP ${res.status}`);
    }
}

/**
 * Download an encrypted chunk as binary.
 * Returns { data: ArrayBuffer, iv: string (base64), lastChunk: boolean, chunkNo: number }
 */
export async function downloadChunk(transferCode, chunkNo) {
    const res = await fetch(`${BASE_URL}/transfers/${transferCode}/chunks/${chunkNo}`);

    if (!res.ok) {
        const err = await res.json().catch(() => ({ message: res.statusText }));
        throw new Error(err.message || `HTTP ${res.status}`);
    }

    const data = await res.arrayBuffer();
    const iv = res.headers.get('X-Chunk-IV');
    const lastChunk = res.headers.get('X-Last-Chunk') === 'true';
    const returnedChunkNo = parseInt(res.headers.get('X-Chunk-No') || chunkNo, 10);
    const chunkHash = res.headers.get('X-Chunk-Hash');

    return { data, iv, lastChunk, chunkNo: returnedChunkNo, chunkHash };
}

export async function acknowledgeChunk(transferCode) {
    return request('POST', `/transfers/${transferCode}/ack`);
}

export async function cancelTransfer(transferCode) {
    return request('DELETE', `/transfers/${transferCode}`);
}
