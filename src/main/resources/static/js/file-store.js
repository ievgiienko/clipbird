/**
 * IndexedDB-based local file storage for source device files.
 * Files are stored locally and read in chunks for transfer.
 */

const DB_NAME = 'SecureClipboardFiles';
const DB_VERSION = 1;
const STORE_NAME = 'files';

let db = null;

async function getDb() {
    if (db) return db;

    return new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, DB_VERSION);
        request.onerror = () => reject(request.error);
        request.onupgradeneeded = (event) => {
            const database = event.target.result;
            if (!database.objectStoreNames.contains(STORE_NAME)) {
                database.createObjectStore(STORE_NAME, { keyPath: 'fileCode' });
            }
        };
        request.onsuccess = () => {
            db = request.result;
            resolve(db);
        };
    });
}

/**
 * Store a file locally by its file code.
 */
export async function storeFile(fileCode, file) {
    const database = await getDb();
    return new Promise((resolve, reject) => {
        const tx = database.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);
        store.put({
            fileCode,
            name: file.name,
            type: file.type,
            size: file.size,
            data: file
        });
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
    });
}

/**
 * Get stored file metadata (without data).
 */
export async function getFileMeta(fileCode) {
    const database = await getDb();
    return new Promise((resolve, reject) => {
        const tx = database.transaction(STORE_NAME, 'readonly');
        const store = tx.objectStore(STORE_NAME);
        const request = store.get(fileCode);
        request.onsuccess = () => {
            if (request.result) {
                const { data, ...meta } = request.result;
                resolve(meta);
            } else {
                resolve(null);
            }
        };
        request.onerror = () => reject(request.error);
    });
}

/**
 * Read a chunk of a stored file.
 * Returns an ArrayBuffer.
 * @param {string} fileCode
 * @param {number} byteOffset - absolute byte position to start reading from
 * @param {number} chunkSize - number of bytes to read
 */
export async function readChunk(fileCode, byteOffset, chunkSize) {
    const database = await getDb();
    return new Promise((resolve, reject) => {
        const tx = database.transaction(STORE_NAME, 'readonly');
        const store = tx.objectStore(STORE_NAME);
        const request = store.get(fileCode);
        request.onsuccess = () => {
            if (!request.result) {
                reject(new Error('File not found in local storage'));
                return;
            }
            const file = request.result.data;
            const start = byteOffset;
            const end = Math.min(start + chunkSize, file.size);
            const blob = file.slice(start, end);

            const reader = new FileReader();
            reader.onload = () => resolve(reader.result);
            reader.onerror = () => reject(reader.error);
            reader.readAsArrayBuffer(blob);
        };
        request.onerror = () => reject(request.error);
    });
}

/**
 * Remove a file from local storage.
 */
export async function removeFile(fileCode) {
    const database = await getDb();
    return new Promise((resolve, reject) => {
        const tx = database.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);
        store.delete(fileCode);
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
    });
}

/**
 * Clear all files from local storage.
 */
export async function clearAll() {
    const database = await getDb();
    return new Promise((resolve, reject) => {
        const tx = database.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);
        store.clear();
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
    });
}
