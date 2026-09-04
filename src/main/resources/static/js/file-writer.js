/**
 * Streaming file writer for target-side chunk assembly.
 *
 * Primary: Origin Private File System (OPFS) — writes chunks directly to disk,
 * constant memory regardless of file size. Works in Chromium 102+, Firefox 111+, Safari 15.2+.
 *
 * Fallback: In-memory chunk accumulation (same as legacy behavior).
 * Used when OPFS is unavailable. Shows console warning for large files.
 */

const OPFS_DIR = '.clipbird-transfers';
const LARGE_FILE_WARN_BYTES = 500 * 1024 * 1024; // 500 MB

/**
 * Detect whether OPFS is available in this browser.
 */
let _opfsSupported = null;
async function isOpfsSupported() {
    if (_opfsSupported !== null) return _opfsSupported;
    try {
        const root = await navigator.storage.getDirectory();
        // Verify writable file support
        const testDir = await root.getDirectoryHandle(OPFS_DIR, { create: true });
        const testFile = await testDir.getFileHandle('_probe', { create: true });
        const writable = await testFile.createWritable();
        await writable.close();
        await testDir.removeEntry('_probe');
        _opfsSupported = true;
    } catch {
        _opfsSupported = false;
    }
    return _opfsSupported;
}

/**
 * Create a writer for a transfer. Chooses OPFS or in-memory fallback.
 * @param {string} transferCode — unique ID for the transfer
 * @param {number} totalSize — expected total file size in bytes
 * @returns {Promise<OpfsWriter|MemoryWriter>}
 */
export async function createWriter(transferCode, totalSize) {
    if (await isOpfsSupported()) {
        return new OpfsWriter(transferCode);
    }
    if (totalSize > LARGE_FILE_WARN_BYTES) {
        console.warn(`OPFS unavailable. Accumulating ${(totalSize / 1024 / 1024).toFixed(0)} MB in memory.`);
    }
    return new MemoryWriter();
}

/**
 * Remove orphaned temp files from OPFS (e.g., from interrupted transfers).
 */
export async function cleanupOrphans() {
    if (!(await isOpfsSupported())) return;
    try {
        const root = await navigator.storage.getDirectory();
        const dir = await root.getDirectoryHandle(OPFS_DIR, { create: false });
        for await (const name of dir.keys()) {
            try {
                await dir.removeEntry(name);
            } catch { /* best effort */ }
        }
    } catch { /* dir doesn't exist — nothing to clean */ }
}

// ─── OPFS Writer ────────────────────────────────────────────

class OpfsWriter {
    constructor(transferCode) {
        this._transferCode = transferCode;
        this._writable = null;
        this._handle = null;
        this._position = 0;
    }

    async _ensureOpen() {
        if (this._writable) return;
        const root = await navigator.storage.getDirectory();
        const dir = await root.getDirectoryHandle(OPFS_DIR, { create: true });
        this._handle = await dir.getFileHandle(this._transferCode, { create: true });
        this._writable = await this._handle.createWritable();
    }

    async writeChunk(data) {
        await this._ensureOpen();
        await this._writable.write({ type: 'write', position: this._position, data });
        this._position += data.byteLength;
    }

    async getFile() {
        if (this._writable) {
            await this._writable.close();
            this._writable = null;
        }
        return this._handle.getFile();
    }

    async cleanup() {
        try {
            if (this._writable) {
                await this._writable.close();
                this._writable = null;
            }
        } catch { /* ignore */ }
        try {
            const root = await navigator.storage.getDirectory();
            const dir = await root.getDirectoryHandle(OPFS_DIR, { create: false });
            await dir.removeEntry(this._transferCode);
        } catch { /* ignore */ }
    }
}

// ─── In-Memory Fallback Writer ──────────────────────────────

class MemoryWriter {
    constructor() {
        this._chunks = [];
    }

    async writeChunk(data) {
        this._chunks.push(new Uint8Array(data));
    }

    async getFile() {
        return new Blob(this._chunks);
    }

    async cleanup() {
        this._chunks = [];
    }
}
