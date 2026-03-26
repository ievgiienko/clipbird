/**
 * Main application entry point.
 * Orchestrates all modules: crypto, api, ws, file-store, state, ui, router, qr.
 *
 * File transfer features:
 * - Sequential transfers: one chunk at a time per transfer
 * - Adaptive chunk sizing: measures throughput on first chunk, adjusts for remaining
 * - Binary transport: encrypted chunks sent as raw bytes, not base64 JSON
 */

import * as crypto from './crypto.js';
import * as api from './api.js';
import { ClipboardWebSocket } from './ws.js';
import * as fileStore from './file-store.js';
import { getState, setState, resetState, generateDeviceId } from './state.js';
import * as ui from './ui.js';
import * as router from './router.js';
import { generateQR, toSvg } from './qr.js';
import * as passwordStrength from './password-strength.js';
import { initTheme } from './theme.js';
import { initPwa } from './pwa.js';
import * as fileWriter from './file-writer.js';

const CHUNK_SIZE = 1 * 1024 * 1024; // 1 MB default
const TEXT_DEBOUNCE_MS = 500;

// Adaptive chunk size constants
const MIN_CHUNK_SIZE = 256 * 1024;      // 256 KB
const MAX_CHUNK_SIZE = 5 * 1024 * 1024;  // 5 MB
const ADAPTIVE_WARMUP_CHUNKS = 1;        // Measure first N chunks before adapting

let ws = null;

// --- Initialization ---

document.addEventListener('DOMContentLoaded', () => {
    initTheme();
    initPwa();
    fileWriter.cleanupOrphans();
    setState({ deviceId: generateDeviceId() });
    bindEvents();
    passwordStrength.bind('create-password', 'password-strength');
    router.init(onRouteChange);
});

function bindEvents() {
    // Create page
    document.getElementById('btn-create').addEventListener('click', handleCreate);
    document.getElementById('btn-generate-password').addEventListener('click', handleGeneratePassword);
    onEnterKey('create-password-confirm', handleCreate);

    // Open page
    document.getElementById('btn-open').addEventListener('click', handleOpen);
    onEnterKey('open-password', handleOpen);

    // URL-based open (clipboard locked state)
    document.getElementById('btn-url-open').addEventListener('click', handleUrlOpen);
    onEnterKey('url-password', handleUrlOpen);

    // Clipboard page
    document.getElementById('btn-delete').addEventListener('click', () => ui.showDeleteModal());
    document.getElementById('btn-copy-link').addEventListener('click', handleCopyLink);
    document.getElementById('btn-show-qr').addEventListener('click', handleShowQr);
    document.getElementById('clipboard-text').addEventListener('input', handleTextInput);

    // File handling
    document.getElementById('btn-add-file').addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        document.getElementById('file-input').click();
    });
    document.getElementById('file-input').addEventListener('change', handleFilesSelected);

    // Drag and drop
    const dropZone = document.getElementById('drop-zone');
    dropZone.addEventListener('dragenter', handleDragEnter);
    dropZone.addEventListener('dragover', handleDragOver);
    dropZone.addEventListener('dragleave', handleDragLeave);
    dropZone.addEventListener('drop', handleDrop);

    // Delegate file/transfer action clicks
    document.getElementById('file-list').addEventListener('click', handleFileAction);
    document.getElementById('transfer-list').addEventListener('click', handleTransferAction);
    document.getElementById('download-all-container').addEventListener('click', handleDownloadAllAction);

    // QR modal
    document.getElementById('btn-close-qr').addEventListener('click', ui.hideQrModal);
    document.getElementById('btn-qr-copy').addEventListener('click', () => {
        const state = getState();
        ui.copyToClipboard(ui.getClipboardUrl(state.clipboardCode));
    });
    document.getElementById('qr-modal').addEventListener('click', (e) => {
        if (e.target === e.currentTarget) ui.hideQrModal();
    });
    document.getElementById('qr-trusted-device').addEventListener('change', handleQrTrustedToggle);

    // Delete confirmation modal
    document.getElementById('btn-confirm-delete').addEventListener('click', handleDelete);
    document.getElementById('btn-cancel-delete').addEventListener('click', ui.hideDeleteModal);
    document.getElementById('btn-close-delete').addEventListener('click', ui.hideDeleteModal);
    document.getElementById('confirm-delete-modal').addEventListener('click', (e) => {
        if (e.target === e.currentTarget) ui.hideDeleteModal();
    });

    // Deleted overlay: Go Home button
    document.getElementById('btn-deleted-go-home').addEventListener('click', cleanupAndGoHome);

    // Keyboard: Escape closes modals
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            ui.hideQrModal();
            ui.hideDeleteModal();
        }
    });
}

function onEnterKey(inputId, handler) {
    document.getElementById(inputId).addEventListener('keydown', (e) => {
        if (e.key === 'Enter') { e.preventDefault(); handler(); }
    });
}

// --- Route change handler ---

function onRouteChange(route) {
    const state = getState();

    // Clean up WS and overlays if navigating away from clipboard
    if (route.page !== 'clipboard') {
        if (ws) { ws.disconnect(); ws = null; }
        ui.hideDeletedOverlay();
    }

    if (route.page === 'clipboard' && route.code) {
        // Trusted-device flow: read password from URL fragment and strip it immediately.
        // The fragment is never sent to the server (RFC 3986).
        // replaceState removes it from the address bar and current history entry.
        const fragmentPassword = readAndStripFragment();

        if (state.clipboardCode === route.code && state.cryptoKey) {
            // Already authenticated for this clipboard
            ui.showUnlockedState(route.code);
        } else if (fragmentPassword) {
            // Auto-authenticate using password from QR fragment
            autoOpenFromFragment(route.code, fragmentPassword);
        } else {
            // Need password — show manual entry
            ui.showLockedState(route.code);
        }
    }

    if (route.page === 'create') {
        document.getElementById('create-password').value = '';
        document.getElementById('create-password-confirm').value = '';
        passwordStrength.reset('password-strength');
        ui.hideError('create-error');
        setTimeout(() => document.getElementById('create-password').focus(), 50);
    }

    if (route.page === 'open') {
        document.getElementById('open-code').value = '';
        document.getElementById('open-password').value = '';
        ui.hideError('open-error');
        setTimeout(() => document.getElementById('open-code').focus(), 50);
    }
}

// --- Fragment handling (trusted-device flow) ---

/**
 * Read the URL fragment (hash) and immediately strip it from the address bar.
 * Returns the decoded fragment value, or null if no fragment was present.
 * The fragment is never sent to the server (RFC 3986).
 * replaceState removes it from the address bar and current history entry.
 */
function readAndStripFragment() {
    const hash = window.location.hash;
    if (!hash || hash.length <= 1) return null;

    const fragment = decodeURIComponent(hash.substring(1));

    // Strip fragment immediately to minimize exposure
    history.replaceState(null, '', window.location.pathname);

    return fragment;
}

/**
 * Attempt to open a clipboard using a password extracted from the URL fragment.
 * On failure, falls back to the manual password entry UI.
 */
async function autoOpenFromFragment(code, password) {
    // Show a transient locked state while authenticating
    ui.showLockedState(code);
    try {
        await authenticateAndOpen(code, password, 'locked-error');
    } catch {
        // authenticateAndOpen already shows errors via showError;
        // ensure the locked UI remains so the user can enter password manually.
        ui.showLockedState(code);
    }
}

// --- Generate random password ---

function handleGeneratePassword() {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+';
    const length = 16;
    const array = new Uint32Array(length);
    window.crypto.getRandomValues(array);
    let password = '';
    for (let i = 0; i < length; i++) {
        password += chars[array[i] % chars.length];
    }

    const input = document.getElementById('create-password');
    input.value = password;
    input.type = 'text';
    setTimeout(() => { input.type = 'password'; }, 3000);

    // Trigger strength indicator update
    input.dispatchEvent(new Event('input', { bubbles: true }));

    // Clear confirm field so user must re-type
    document.getElementById('create-password-confirm').value = '';
    document.getElementById('create-password-confirm').focus();
}

// --- Create clipboard ---

async function handleCreate() {
    const password = document.getElementById('create-password').value;
    const confirm = document.getElementById('create-password-confirm').value;

    if (!password || password.length < 4) {
        ui.showError('create-error', 'Password must be at least 4 characters');
        return;
    }
    if (password !== confirm) {
        ui.showError('create-error', 'Passwords do not match');
        return;
    }

    const btn = document.getElementById('btn-create');
    btn.disabled = true;

    try {
        // Phase 1: Create clipboard (server generates salt)
        const result = await api.createClipboard();

        // Phase 2: Compute password hash using server salt, then set it
        const passwordHash = await crypto.computePasswordHash(password, result.salt);
        await api.setPassword(result.clipboardCode, passwordHash);

        // Derive encryption key from password + server salt
        const key = await crypto.deriveKey(password, result.salt);

        setState({
            clipboardCode: result.clipboardCode,
            password,
            passwordHash,
            cryptoKey: key,
            salt: result.salt,
        });

        // Navigate to clipboard page
        router.navigate(`/c/${result.clipboardCode}`);

        // Connect after navigation (route handler will show unlocked state)
        connectToClipboard(result.clipboardCode, passwordHash);
    } catch (err) {
        ui.showError('create-error', err.message);
    } finally {
        btn.disabled = false;
    }
}

// --- Open existing clipboard (from /open page) ---

async function handleOpen() {
    const code = document.getElementById('open-code').value.trim();
    const password = document.getElementById('open-password').value;

    if (!code) {
        ui.showError('open-error', 'Please enter a clipboard ID');
        return;
    }
    if (!password) {
        ui.showError('open-error', 'Please enter the password');
        return;
    }

    const btn = document.getElementById('btn-open');
    btn.disabled = true;

    try {
        await authenticateAndOpen(code, password, 'open-error');
    } finally {
        btn.disabled = false;
    }
}

// --- Open clipboard from URL (locked state) ---

async function handleUrlOpen() {
    const route = router.getRoute();
    const code = route.code;
    const password = document.getElementById('url-password').value;

    if (!password) {
        ui.showError('locked-error', 'Please enter the password');
        return;
    }

    const btn = document.getElementById('btn-url-open');
    btn.disabled = true;

    try {
        await authenticateAndOpen(code, password, 'locked-error');
    } finally {
        btn.disabled = false;
    }
}

// --- Shared authentication flow ---

async function authenticateAndOpen(code, password, errorElementId) {
    try {
        const handshake = await api.getHandshake(code);

        if (handshake.status !== 'ACTIVE') {
            ui.showError(errorElementId, 'Clipboard is no longer active');
            return;
        }

        const passwordHash = await crypto.computePasswordHash(password, handshake.salt);
        const valid = await api.verifyPassword(code, passwordHash);
        if (!valid) {
            ui.showError(errorElementId, 'Invalid password');
            return;
        }

        const key = await crypto.deriveKey(password, handshake.salt);

        setState({
            clipboardCode: code,
            password,
            passwordHash,
            cryptoKey: key,
            salt: handshake.salt,
        });

        // Navigate to clipboard page (if not already there)
        if (location.pathname !== `/c/${code}`) {
            router.navigate(`/c/${code}`);
        } else {
            // Already on the clipboard page, just switch to unlocked state
            ui.showUnlockedState(code);
        }

        connectToClipboard(code, passwordHash);
    } catch (err) {
        ui.showError(errorElementId, err.message);
    }
}

// --- Connect and load clipboard ---

async function connectToClipboard(clipboardCode, passwordHash) {
    const state = getState();

    ui.showUnlockedState(clipboardCode);

    // Connect WebSocket
    ws = new ClipboardWebSocket();
    setupWsHandlers(ws);
    ws.connect(clipboardCode, state.deviceId, passwordHash);

    // Load current state
    try {
        const clipState = await api.getClipboardState(clipboardCode);
        setState({ connectedDevices: clipState.connectedDevices });
        ui.setDeviceCount(clipState.connectedDevices);

        // Decrypt and display text
        if (clipState.text && clipState.text.ciphertext) {
            try {
                const plaintext = await crypto.decryptText(state.cryptoKey, clipState.text.ciphertext, clipState.text.iv);
                ui.setTextContent(plaintext);
                setState({ textVersion: clipState.text.version });
                ui.setTextVersion(clipState.text.version);
            } catch {
                ui.showError('clipboard-error', 'Failed to decrypt text. Wrong password?');
            }
        }

        // Display files
        if (clipState.files && clipState.files.length > 0) {
            setState({ files: clipState.files });
            await ui.renderFileList(clipState.files);
            updateDownloadAllButton();
        }
    } catch (err) {
        ui.showError('clipboard-error', err.message);
    }
}

// --- WebSocket handlers ---

function setupWsHandlers(ws) {
    ws.on('connected', () => {
        setState({ connected: true });
        ui.setConnectionStatus(true);
    });

    ws.on('disconnected', () => {
        setState({ connected: false });
        ui.setConnectionStatus(false);
    });

    ws.on('joined', (msg) => {
        setState({ connectedDevices: msg.connectedDevices });
        ui.setDeviceCount(msg.connectedDevices);
    });

    ws.on('device_joined', (msg) => {
        setState({ connectedDevices: msg.connectedDevices });
        ui.setDeviceCount(msg.connectedDevices);
        refreshFileList();
    });

    ws.on('device_left', (msg) => {
        setState({ connectedDevices: msg.connectedDevices });
        ui.setDeviceCount(msg.connectedDevices);
        refreshFileList();
    });

    ws.on('text_updated', async (msg) => {
        const state = getState();
        if (msg.updatedBy === state.deviceId) {
            setState({ textVersion: msg.version });
            ui.setTextVersion(msg.version);
            return;
        }
        try {
            const plaintext = await crypto.decryptText(state.cryptoKey, msg.ciphertext, msg.iv);
            setState({ textVersion: msg.version, isUpdatingText: true });
            ui.setTextContent(plaintext);
            ui.setTextVersion(msg.version);
            setState({ isUpdatingText: false });
        } catch {
            ui.showError('clipboard-error', 'Failed to decrypt incoming text update');
        }
    });

    ws.on('file_added', async (msg) => {
        const state = getState();
        state.files.push(msg.file);
        await ui.renderFileList(state.files);
        updateDownloadAllButton();
    });

    ws.on('file_removed', (msg) => {
        const state = getState();
        state.files = state.files.filter(f => f.fileCode !== msg.fileCode);
        ui.renderFileList(state.files);
        updateDownloadAllButton();
    });

    ws.on('clipboard_deleted', () => {
        ui.showDeletedOverlay();
        if (ws) ws.disconnect();
    });

    ws.on('error', (msg) => {
        ui.showError('clipboard-error', msg.message || 'WebSocket error');
    });

    ws.on('auth_failed', () => {
        ui.showError('clipboard-error', 'Authentication failed. Wrong password.');
        cleanupAndGoHome();
    });

    // Transfer signaling
    ws.on('send_chunk', handleSendChunkRequest);
    ws.on('chunk_ready', handleChunkReady);
    ws.on('transfer_complete', handleTransferComplete);
    ws.on('transfer_cancelled', handleTransferCancelled);
}

// --- Text handling ---

function handleTextInput() {
    const state = getState();
    if (state.isUpdatingText) return;

    const text = ui.getTextContent();
    ui.setTextSize(new TextEncoder().encode(text).length);

    if (state.textUpdateTimer) clearTimeout(state.textUpdateTimer);
    setState({
        textUpdateTimer: setTimeout(() => sendTextUpdate(text), TEXT_DEBOUNCE_MS)
    });
}

async function sendTextUpdate(text) {
    const state = getState();
    if (!state.cryptoKey || !ws || !ws.isConnected()) return;

    try {
        const encrypted = await crypto.encryptText(state.cryptoKey, text);
        ws.send({
            type: 'text_update',
            ciphertext: encrypted.ciphertext,
            iv: encrypted.iv,
            version: state.textVersion
        });
    } catch (err) {
        ui.showError('clipboard-error', 'Failed to encrypt text: ' + err.message);
    }
}

// --- File handling ---

async function processFiles(files) {
    if (!files || files.length === 0) return;

    const state = getState();
    for (const file of files) {
        try {
            const totalChunks = Math.ceil(file.size / CHUNK_SIZE);

            const encName = await crypto.encryptString(state.cryptoKey, file.name);
            const encMime = file.type
                ? await crypto.encryptString(state.cryptoKey, file.type)
                : { ciphertext: '', iv: '' };

            // fileHash is computed incrementally during transfer (per-chunk hash-of-hashes)
            const manifest = await api.addFile(state.clipboardCode, {
                encryptedName: encName.ciphertext,
                encryptedNameIv: encName.iv,
                encryptedMime: encMime.ciphertext,
                encryptedMimeIv: encMime.iv,
                sizeBytes: file.size,
                totalChunks,
                sourceDeviceId: state.deviceId,
                fileHash: null
            });

            await fileStore.storeFile(manifest.fileCode, file);

            state.files.push(manifest);
            await ui.renderFileList(state.files);
            updateDownloadAllButton();

            ws.send({ type: 'file_added', file: manifest });
        } catch (err) {
            ui.showError('clipboard-error', 'Failed to add file: ' + err.message);
        }
    }
}

async function handleFilesSelected(event) {
    await processFiles(event.target.files);
    event.target.value = '';
}

// --- Drag and drop ---

function handleDragEnter(e) {
    e.preventDefault();
    e.stopPropagation();
    ui.setDropZoneActive(true);
}

function handleDragOver(e) {
    e.preventDefault();
    e.stopPropagation();
    ui.setDropZoneActive(true);
}

function handleDragLeave(e) {
    e.preventDefault();
    e.stopPropagation();
    // Only deactivate if leaving the drop zone entirely
    const dz = document.getElementById('drop-zone');
    if (!dz.contains(e.relatedTarget)) {
        ui.setDropZoneActive(false);
    }
}

async function handleDrop(e) {
    e.preventDefault();
    e.stopPropagation();
    ui.setDropZoneActive(false);

    const state = getState();
    if (!state.cryptoKey || !ws || !ws.isConnected()) {
        ui.showError('clipboard-error', 'Connect to a clipboard before adding files');
        return;
    }

    const files = e.dataTransfer.files;
    if (files && files.length > 0) {
        await processFiles(files);
    }
}

// --- File actions ---

function handleFileAction(event) {
    const btn = event.target.closest('button[data-action]');
    if (!btn) return;

    const action = btn.dataset.action;
    const fileCode = btn.dataset.fileCode;

    if (action === 'download') startDownload(fileCode);
    else if (action === 'remove') removeFile(fileCode);
}

function handleTransferAction(event) {
    const btn = event.target.closest('button[data-action]');
    if (!btn) return;

    if (btn.dataset.action === 'cancel-transfer') {
        ws.send({ type: 'transfer_cancel', transferCode: btn.dataset.transferCode });
    }
}

async function removeFile(fileCode) {
    const state = getState();
    try {
        await api.removeFile(state.clipboardCode, fileCode);
        await fileStore.removeFile(fileCode);
        state.files = state.files.filter(f => f.fileCode !== fileCode);
        await ui.renderFileList(state.files);
        updateDownloadAllButton();
        ws.send({ type: 'file_removed', fileCode });
    } catch (err) {
        ui.showError('clipboard-error', 'Failed to remove file: ' + err.message);
    }
}

// ============================================================
// ADAPTIVE CHUNK SIZE
// ============================================================

/**
 * Compute an optimal chunk size based on measured throughput.
 *
 * Algorithm:
 * 1. Measure round-trip time for the first chunk (upload + relay + download).
 * 2. Calculate effective throughput in bytes/second.
 * 3. Target a chunk transfer time of ~2 seconds for good responsiveness.
 * 4. Clamp to [minChunkSize, maxChunkSize].
 * 5. Round down to nearest 256 KB for clean alignment.
 *
 * @param {number} bytesTransferred - bytes in the measured chunk
 * @param {number} elapsedMs - wall-clock time for that chunk's full cycle
 * @param {number} minSize - minimum chunk size
 * @param {number} maxSize - maximum chunk size
 * @returns {number} optimal chunk size in bytes
 */
function computeAdaptiveChunkSize(bytesTransferred, elapsedMs, minSize, maxSize) {
    if (elapsedMs <= 0) return CHUNK_SIZE;

    const throughputBps = (bytesTransferred / elapsedMs) * 1000;
    // Target: each chunk should take ~2 seconds to transfer
    const TARGET_CHUNK_DURATION_S = 2;
    let optimal = Math.floor(throughputBps * TARGET_CHUNK_DURATION_S);

    // Round down to 256 KB boundary
    const ALIGNMENT = 256 * 1024;
    optimal = Math.floor(optimal / ALIGNMENT) * ALIGNMENT;

    return Math.max(minSize, Math.min(maxSize, optimal || minSize));
}

// ============================================================
// FILE DOWNLOAD (TARGET DEVICE) — SEQUENTIAL
// ============================================================

/**
 * Start downloading a file.
 * Creates a transfer and requests the first chunk from the source.
 */
async function startDownload(fileCode) {
    const state = getState();
    const file = state.files.find(f => f.fileCode === fileCode);
    if (!file) return;

    try {
        const transfer = await api.createTransfer(state.clipboardCode, fileCode, state.deviceId);

        let fileName = fileCode;
        try {
            fileName = await crypto.decryptString(state.cryptoKey, file.encryptedName, file.encryptedNameIv);
        } catch { /* fallback */ }

        // Create streaming file writer (OPFS or in-memory fallback)
        const writer = await fileWriter.createWriter(transfer.transferCode, file.sizeBytes);

        state.activeTransfers.set(transfer.transferCode, {
            ...transfer,
            fileName,
            direction: 'download',
            currentChunk: 0,
            writer,
            chunkHashes: [],
            expectedFileHash: null,
            // Adaptive chunk size state
            chunkSize: transfer.defaultChunkSize || CHUNK_SIZE,
            minChunkSize: transfer.minChunkSize || MIN_CHUNK_SIZE,
            maxChunkSize: transfer.maxChunkSize || MAX_CHUNK_SIZE,
            adaptiveResolved: false,
            chunkTimings: [],
            // Cumulative byte offset for variable-size chunk reads
            nextByteOffset: 0,
        });
        ui.renderTransfers(state.activeTransfers);
        onBatchTransferStarted(transfer.transferCode);

        // Request the first chunk from the source (byteOffset=0 for first chunk)
        requestChunkFromSource(transfer.transferCode, fileCode, file.sourceDeviceId, 0, transfer.defaultChunkSize || CHUNK_SIZE, 0);
        // Record start time for adaptive sizing measurement
        state.activeTransfers.get(transfer.transferCode)._chunkStartTime = performance.now();
    } catch (err) {
        ui.showError('clipboard-error', 'Failed to start download: ' + err.message);
        if (batchDownload && batchDownload.active) {
            batchDownload.failed++;
            updateDownloadAllButton();
            startNextBatchDownload();
        }
    }
}

/**
 * Send a WebSocket message requesting the source device to upload a chunk.
 */
function requestChunkFromSource(transferCode, fileCode, sourceDeviceId, chunkNo, chunkSize, byteOffset) {
    const msg = {
        type: 'transfer_request',
        transferCode,
        fileCode,
        sourceDeviceId,
        chunkNo,
        chunkSize
    };
    if (byteOffset != null) msg.byteOffset = byteOffset;
    ws.send(msg);
}

// ============================================================
// FILE UPLOAD (SOURCE DEVICE)
// ============================================================

// Source-side per-transfer state for incremental hash tracking.
// Maps transferCode → { chunkHashes: string[], totalChunks: number }
const sourceTransfers = new Map();

/**
 * Handle server's request to upload a specific chunk.
 * Uses binary encryption (no base64) and binary upload.
 * Computes per-chunk SHA-256 hash of plaintext for integrity verification.
 * On the last chunk, computes and sends the file-level hash-of-hashes.
 */
async function handleSendChunkRequest(msg) {
    const state = getState();
    const { transferCode, fileCode, chunkNo } = msg;

    try {
        const file = state.files.find(f => f.fileCode === fileCode);
        const totalChunks = file ? file.totalChunks : 1;

        // Initialize per-transfer hash tracking
        if (!sourceTransfers.has(transferCode)) {
            sourceTransfers.set(transferCode, { chunkHashes: [], totalChunks });
        }
        const srcTransfer = sourceTransfers.get(transferCode);

        // Use the chunk size from the message (adaptive) or fall back to file/default
        const chunkSize = msg.chunkSize || (file ? file.chunkSize : CHUNK_SIZE);
        // Use explicit byteOffset if provided, otherwise fall back to chunkNo * chunkSize
        const byteOffset = msg.byteOffset != null ? msg.byteOffset : chunkNo * chunkSize;
        const chunkData = await fileStore.readChunk(fileCode, byteOffset, chunkSize);

        // Compute per-chunk integrity hash on plaintext before encryption
        const chunkHash = await crypto.computeChunkHash(chunkData);
        srcTransfer.chunkHashes[chunkNo] = chunkHash;

        // Binary encryption: returns { data: ArrayBuffer, iv: Uint8Array }
        const encrypted = await crypto.encryptChunkBinary(state.cryptoKey, chunkData);
        // IV as base64 string for the header/form field
        const ivB64 = crypto.bytesToBase64(encrypted.iv);
        await api.uploadChunk(transferCode, chunkNo, encrypted.data, ivB64, chunkHash);

        const transfer = await api.getTransfer(transferCode);

        const uploadedMsg = {
            type: 'chunk_uploaded',
            transferCode,
            chunkNo,
            targetDeviceId: transfer.targetDeviceId
        };

        // On last chunk, compute file-level hash-of-hashes and include it
        if (chunkNo === totalChunks - 1) {
            uploadedMsg.fileHash = await crypto.computeHashOfHashes(srcTransfer.chunkHashes);
            sourceTransfers.delete(transferCode);
        }

        ws.send(uploadedMsg);
    } catch (err) {
        console.error('Failed to send chunk:', err);
        ui.showError('clipboard-error', 'Failed to send file chunk: ' + err.message);
    }
}

// ============================================================
// CHUNK READY (TARGET DEVICE) — SEQUENTIAL
// ============================================================

/**
 * Handle notification that a chunk is ready on the server.
 * Download it, decrypt it, store it, and ACK.
 * Sequential: one chunk at a time, server requests the next chunk after ACK.
 */
async function handleChunkReady(msg) {
    const state = getState();
    const { transferCode, chunkNo } = msg;
    const transfer = state.activeTransfers.get(transferCode);
    if (!transfer) return;

    // Guard against duplicate chunk_ready for same chunkNo
    if (transfer._processedChunks && transfer._processedChunks.has(chunkNo)) return;

    // Capture file-level hash-of-hashes if sent with last chunk
    if (msg.fileHash) {
        transfer.expectedFileHash = msg.fileHash;
    }

    try {
        // Binary download: returns { data, iv, lastChunk, chunkNo, chunkHash }
        const chunk = await api.downloadChunk(transferCode, chunkNo);
        // Decrypt using binary path: convert IV from base64 to Uint8Array
        const ivBytes = crypto.base64ToBytes(chunk.iv);
        const decrypted = await crypto.decryptChunkBinary(state.cryptoKey, chunk.data, ivBytes);

        // Per-chunk integrity verification
        if (chunk.chunkHash) {
            const actualHash = await crypto.computeChunkHash(decrypted);
            if (actualHash !== chunk.chunkHash) {
                throw new Error(`Chunk ${chunkNo} integrity check failed`);
            }
            transfer.chunkHashes[chunkNo] = chunk.chunkHash;
        }

        // Mark chunk as processed (for duplicate guard)
        if (!transfer._processedChunks) transfer._processedChunks = new Set();
        transfer._processedChunks.add(chunkNo);

        // Write decrypted chunk to OPFS/storage (constant memory)
        await transfer.writer.writeChunk(decrypted);

        // Update progress
        const completedChunks = transfer._processedChunks.size;
        transfer.currentChunk = completedChunks;
        transfer.status = `${completedChunks}/${transfer.totalChunks}`;
        ui.renderTransfers(state.activeTransfers);

        // Measure timing for adaptive chunk sizing
        if (!transfer.adaptiveResolved && transfer._chunkStartTime) {
            const elapsed = performance.now() - transfer._chunkStartTime;
            transfer.chunkTimings.push({
                bytes: chunk.data.byteLength,
                elapsedMs: elapsed
            });

            if (transfer.chunkTimings.length >= ADAPTIVE_WARMUP_CHUNKS) {
                const totalBytes = transfer.chunkTimings.reduce((s, t) => s + t.bytes, 0);
                const totalMs = transfer.chunkTimings.reduce((s, t) => s + t.elapsedMs, 0);
                const newSize = computeAdaptiveChunkSize(
                    totalBytes, totalMs,
                    transfer.minChunkSize, transfer.maxChunkSize
                );
                if (newSize !== transfer.chunkSize) {
                    console.log(`Adaptive chunk size: ${transfer.chunkSize} -> ${newSize} bytes ` +
                        `(${Math.round(totalBytes / totalMs * 1000 / 1024)} KB/s)`);
                    transfer.chunkSize = newSize;
                }
                transfer.adaptiveResolved = true;
            }
        }
        // Reset timer for next chunk
        transfer._chunkStartTime = performance.now();

        // Track cumulative byte offset for variable-size chunk reads
        transfer.nextByteOffset = (transfer.nextByteOffset || 0) + decrypted.byteLength;

        // ACK this chunk. Server will request the next chunk from the source.
        ws.send({ type: 'chunk_ack', transferCode, chunkNo, chunkSize: transfer.chunkSize, byteOffset: transfer.nextByteOffset });
    } catch (err) {
        console.error('Failed to download chunk:', err);
        transfer.status = 'Error: ' + err.message;
        ui.renderTransfers(state.activeTransfers);
    }
}

// ============================================================
// TRANSFER COMPLETE
// ============================================================

async function handleTransferComplete(msg) {
    const state = getState();
    const transfer = state.activeTransfers.get(msg.transferCode);
    if (!transfer || transfer.direction !== 'download') {
        state.activeTransfers.delete(msg.transferCode);
        ui.renderTransfers(state.activeTransfers);
        return;
    }

    try {
        // Verify file-level integrity via hash-of-hashes (no full-file read needed)
        if (transfer.expectedFileHash && transfer.chunkHashes.length > 0) {
            transfer.status = 'Verifying...';
            ui.renderTransfers(state.activeTransfers);

            const actualFileHash = await crypto.computeHashOfHashes(transfer.chunkHashes);
            if (actualFileHash !== transfer.expectedFileHash) {
                throw new Error('Integrity check failed: downloaded file does not match the original');
            }
        }

        // Get assembled file from writer (OPFS File or in-memory Blob)
        const blob = await transfer.writer.getFile();

        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = transfer.fileName || 'download';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);

        // Revoke blob URL after a delay — the browser needs time to start the download.
        // OPFS-backed Files become invalid if their temp file is deleted immediately.
        setTimeout(() => {
            URL.revokeObjectURL(url);
            transfer.writer.cleanup();
        }, 60_000);

        ui.showInfo(`File "${transfer.fileName}" downloaded successfully`);
        state.activeTransfers.delete(msg.transferCode);
        ui.renderTransfers(state.activeTransfers);
        onBatchTransferCompleted(msg.transferCode, true);
    } catch (err) {
        ui.showError('clipboard-error', 'Failed to reconstruct file: ' + err.message);
        if (transfer.writer) await transfer.writer.cleanup();
        state.activeTransfers.delete(msg.transferCode);
        ui.renderTransfers(state.activeTransfers);
        onBatchTransferCompleted(msg.transferCode, false);
    }
}

async function handleTransferCancelled(msg) {
    const state = getState();
    const transfer = state.activeTransfers.get(msg.transferCode);
    if (transfer && transfer.writer) {
        await transfer.writer.cleanup();
    }
    sourceTransfers.delete(msg.transferCode);
    state.activeTransfers.delete(msg.transferCode);
    ui.renderTransfers(state.activeTransfers);
    onBatchTransferCompleted(msg.transferCode, false);
    if (!batchDownload || !batchDownload.active) {
        ui.showInfo('Transfer cancelled');
    }
}

// ============================================================
// DOWNLOAD ALL — SEQUENTIAL BATCH DOWNLOAD
// ============================================================

/**
 * Batch download state. Null when no batch is active.
 * { queue: string[], total: number, completed: number, failed: number, active: true, currentTransferCode: string|null }
 */
let batchDownload = null;

function handleDownloadAllAction(event) {
    const btn = event.target.closest('#btn-download-all');
    if (btn) {
        downloadAll();
        return;
    }
    const cancelBtn = event.target.closest('#btn-cancel-download-all');
    if (cancelBtn) {
        cancelBatchDownload();
    }
}

async function downloadAll() {
    if (batchDownload && batchDownload.active) return; // prevent duplicate

    const state = getState();
    const downloadable = state.files.filter(f =>
        f.sourceDeviceId !== state.deviceId && f.sourceOnline
    );

    if (downloadable.length === 0) return;

    batchDownload = {
        queue: downloadable.map(f => f.fileCode),
        total: downloadable.length,
        completed: 0,
        failed: 0,
        active: true,
        currentTransferCode: null,
    };

    updateDownloadAllButton();
    startNextBatchDownload();
}

function startNextBatchDownload() {
    if (!batchDownload || !batchDownload.active) return;

    if (batchDownload.queue.length === 0) {
        // Batch complete
        const msg = batchDownload.failed > 0
            ? `Batch download finished: ${batchDownload.completed} completed, ${batchDownload.failed} failed`
            : `All ${batchDownload.completed} files downloaded successfully`;
        ui.showInfo(msg);
        batchDownload = null;
        updateDownloadAllButton();
        return;
    }

    const fileCode = batchDownload.queue.shift();
    const state = getState();
    const file = state.files.find(f => f.fileCode === fileCode);

    // Skip if file no longer available
    if (!file || !file.sourceOnline || file.sourceDeviceId === state.deviceId) {
        batchDownload.failed++;
        updateDownloadAllButton();
        startNextBatchDownload();
        return;
    }

    startDownload(fileCode);
}

function cancelBatchDownload() {
    if (!batchDownload) return;

    // Cancel current in-progress transfer if any
    if (batchDownload.currentTransferCode) {
        ws.send({ type: 'transfer_cancel', transferCode: batchDownload.currentTransferCode });
    }

    batchDownload.active = false;
    batchDownload = null;
    updateDownloadAllButton();
    ui.showInfo('Batch download cancelled');
}

function onBatchTransferStarted(transferCode) {
    if (batchDownload && batchDownload.active) {
        batchDownload.currentTransferCode = transferCode;
    }
}

function onBatchTransferCompleted(transferCode, success) {
    if (!batchDownload || !batchDownload.active) return;
    if (batchDownload.currentTransferCode !== transferCode) return;

    batchDownload.currentTransferCode = null;
    if (success) {
        batchDownload.completed++;
    } else {
        batchDownload.failed++;
    }
    updateDownloadAllButton();
    startNextBatchDownload();
}

function updateDownloadAllButton() {
    const state = getState();
    const downloadableCount = state.files.filter(f =>
        f.sourceDeviceId !== state.deviceId && f.sourceOnline
    ).length;
    ui.renderDownloadAllButton(downloadableCount, batchDownload);
}

// --- Refresh file list ---

async function refreshFileList() {
    const state = getState();
    if (!state.clipboardCode) return;
    try {
        const clipState = await api.getClipboardState(state.clipboardCode);
        setState({ files: clipState.files });
        await ui.renderFileList(clipState.files);
        updateDownloadAllButton();
    } catch { /* ignore refresh errors */ }
}

// --- Copy link ---

function handleCopyLink() {
    const state = getState();
    ui.copyToClipboard(ui.getClipboardUrl(state.clipboardCode));
}

// --- QR code ---

function handleShowQr() {
    // Reset checkbox each time the modal is opened
    document.getElementById('qr-trusted-device').checked = false;
    updateQrNote(false);
    renderQrCode(false);
    document.getElementById('qr-modal').style.display = 'flex';
}

function handleQrTrustedToggle() {
    const trusted = document.getElementById('qr-trusted-device').checked;
    updateQrNote(trusted);
    renderQrCode(trusted);
}

function renderQrCode(includeTrustedPassword) {
    const state = getState();
    const baseUrl = ui.getClipboardUrl(state.clipboardCode);
    const qrUrl = includeTrustedPassword
        ? `${baseUrl}#${encodeURIComponent(state.password)}`
        : baseUrl;
    try {
        const matrix = generateQR(qrUrl);
        const svg = toSvg(matrix, 6);
        // Always display the clean URL (never show password)
        ui.showQrCode(svg, baseUrl);
    } catch (err) {
        ui.showError('clipboard-error', 'Failed to generate QR code: ' + err.message);
    }
}

function updateQrNote(trusted) {
    const note = document.getElementById('qr-note');
    if (trusted) {
        note.textContent = 'The QR code now includes the password. The scanning device will be able to open the clipboard directly.';
    } else {
        note.textContent = 'Password is not included in the link. It must be entered manually on the other device.';
    }
}

// --- Delete clipboard ---

function handleDelete() {
    ui.hideDeleteModal();
    try {
        ws.send({ type: 'delete_clipboard' });
    } catch (err) {
        ui.showError('clipboard-error', 'Failed to delete clipboard: ' + err.message);
    }
}

// --- Navigation helpers ---

function cleanupAndGoHome() {
    if (ws) { ws.disconnect(); ws = null; }
    resetState();
    setState({ deviceId: generateDeviceId() });
    fileStore.clearAll();
    ui.setTextContent('');
    ui.setTextVersion(0);
    ui.setConnectionStatus(false);
    ui.setDeviceCount(0);
    ui.hideDeletedOverlay();
    router.navigate('/');
}
