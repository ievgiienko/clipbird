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

export async function uploadChunk(transferCode, chunkNo, data, iv) {
    return request('PUT', `/transfers/${transferCode}/chunks/${chunkNo}`, { data, iv });
}

export async function downloadChunk(transferCode, chunkNo) {
    return request('GET', `/transfers/${transferCode}/chunks/${chunkNo}`);
}

export async function acknowledgeChunk(transferCode) {
    return request('POST', `/transfers/${transferCode}/ack`);
}

export async function cancelTransfer(transferCode) {
    return request('DELETE', `/transfers/${transferCode}`);
}
