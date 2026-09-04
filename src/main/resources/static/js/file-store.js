/**
 * IndexedDB-based local file storage for source device files.
 * Files are stored locally and read in chunks for transfer.
 *
 * Not every engine accepts a File in IndexedDB: WebKit rejects the structured
 * clone with "BlobURLs are not yet supported", and storage can also be refused
 * because of quota or private mode. In those cases the handle is kept in memory
 * for the lifetime of the page instead. Chunks are read by slicing either way,
 * so memory does not grow with file size, but a memory-held file does not
 * survive a reload.
 */

const DB_NAME = 'SecureClipboardFiles';
const DB_VERSION = 1;
const STORE_NAME = 'files';

let dbPromise = null;

const memoryFallback = new Map();

/**
 * Callers arrive in bursts, so the in-flight open is shared instead of opening a
 * connection per call. An open blocked by another tab holding an older version
 * never fires onsuccess, so that case is turned into a rejection rather than a
 * promise that hangs for the lifetime of the page.
 */
function getDb() {
    if (dbPromise) return dbPromise;

    dbPromise = new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, DB_VERSION);
        request.onerror = () => reject(request.error);
        request.onblocked = () => reject(new Error('IndexedDB is blocked by another tab'));
        request.onupgradeneeded = (event) => {
            const database = event.target.result;
            if (!database.objectStoreNames.contains(STORE_NAME)) {
                database.createObjectStore(STORE_NAME, { keyPath: 'fileCode' });
            }
        };
        request.onsuccess = () => resolve(request.result);
    });

    // A failed open must not be cached, or every later call inherits the failure.
    dbPromise.catch(() => { dbPromise = null; });
    return dbPromise;
}

/**
 * Read a byte range of a file as an ArrayBuffer.
 */
function readRange(file, byteOffset, chunkSize) {
    const end = Math.min(byteOffset + chunkSize, file.size);
    return file.slice(byteOffset, end).arrayBuffer();
}

/**
 * Store a file locally by its file code.
 */
export async function storeFile(fileCode, file) {
    const record = {
        fileCode,
        name: file.name,
        type: file.type,
        size: file.size,
        data: file
    };

    try {
        const database = await getDb();
        /** @type {Promise<void>} */
        const stored = new Promise((resolve, reject) => {
            const tx = database.transaction(STORE_NAME, 'readwrite');
            tx.objectStore(STORE_NAME).put(record);
            tx.oncomplete = () => resolve();
            tx.onerror = () => reject(tx.error);
            tx.onabort = () => reject(tx.error || new Error('Transaction aborted'));
        });
        await stored;
        memoryFallback.delete(fileCode);
    } catch (err) {
        console.warn('Local file storage unavailable, keeping file in memory:', err);
        memoryFallback.set(fileCode, record);
    }
}

/**
 * Get stored file metadata (without data).
 *
 * Returns null when the file is definitely absent. Throws when storage cannot be
 * reached, which is a different answer: callers that delete things on absence must
 * not treat "cannot tell" as "not there".
 */
export async function getFileMeta(fileCode) {
    const cached = memoryFallback.get(fileCode);
    if (cached) {
        const { data, ...meta } = cached;
        return meta;
    }

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
    const cached = memoryFallback.get(fileCode);
    if (cached) return readRange(cached.data, byteOffset, chunkSize);

    const database = await getDb();
    const record = await new Promise((resolve, reject) => {
        const tx = database.transaction(STORE_NAME, 'readonly');
        const store = tx.objectStore(STORE_NAME);
        const request = store.get(fileCode);
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    });

    if (!record) throw new Error('File not found in local storage');
    return readRange(record.data, byteOffset, chunkSize);
}

/**
 * Remove a file from local storage.
 */
export async function removeFile(fileCode) {
    memoryFallback.delete(fileCode);

    try {
        const database = await getDb();
        /** @type {Promise<void>} */
        const removed = new Promise((resolve, reject) => {
            const tx = database.transaction(STORE_NAME, 'readwrite');
            const store = tx.objectStore(STORE_NAME);
            store.delete(fileCode);
            tx.oncomplete = () => resolve();
            tx.onerror = () => reject(tx.error);
        });
        await removed;
    } catch (err) {
        console.warn('Could not remove file from local storage:', err);
    }
}

/**
 * Clear all files from local storage.
 */
export async function clearAll() {
    memoryFallback.clear();

    try {
        const database = await getDb();
        /** @type {Promise<void>} */
        const cleared = new Promise((resolve, reject) => {
            const tx = database.transaction(STORE_NAME, 'readwrite');
            const store = tx.objectStore(STORE_NAME);
            store.clear();
            tx.oncomplete = () => resolve();
            tx.onerror = () => reject(tx.error);
        });
        await cleared;
    } catch (err) {
        console.warn('Could not clear local file storage:', err);
    }
}
