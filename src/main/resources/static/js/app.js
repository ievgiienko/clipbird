/**
 * Main application entry point.
 * Orchestrates all modules: crypto, api, ws, file-store, state, ui.
 */

import * as crypto from './crypto.js';
import * as api from './api.js';
import { ClipboardWebSocket } from './ws.js';
import * as fileStore from './file-store.js';
import { getState, setState, resetState, generateDeviceId } from './state.js';
import * as ui from './ui.js';

const CHUNK_SIZE = 5 * 1024 * 1024; // 5 MB
const TEXT_DEBOUNCE_MS = 500;

let ws = null;

// --- Initialization ---

document.addEventListener('DOMContentLoaded', () => {
    setState({ deviceId: generateDeviceId() });
    bindEvents();
});

function bindEvents() {
    document.getElementById('btn-create').addEventListener('click', handleCreate);
    document.getElementById('btn-open').addEventListener('click', handleOpen);
    document.getElementById('btn-delete').addEventListener('click', handleDelete);
    document.getElementById('btn-back').addEventListener('click', handleBack);
    document.getElementById('btn-add-file').addEventListener('click', () => document.getElementById('file-input').click());
    document.getElementById('file-input').addEventListener('change', handleFilesSelected);
    document.getElementById('clipboard-text').addEventListener('input', handleTextInput);
    document.getElementById('btn-deleted-home').addEventListener('click', () => {
        ui.hideDeletedOverlay();
        goHome();
    });

    // Delegate file action clicks
    document.getElementById('file-list').addEventListener('click', handleFileAction);
    document.getElementById('transfer-list').addEventListener('click', handleTransferAction);
}

// --- Create clipboard ---

async function handleCreate() {
    const password = document.getElementById('create-password').value;
    const confirm = document.getElementById('create-password-confirm').value;

    if (!password || password.length < 4) {
        ui.showError('home-error', 'Password must be at least 4 characters');
        return;
    }
    if (password !== confirm) {
        ui.showError('home-error', 'Passwords do not match');
        return;
    }

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

        document.getElementById('create-password').value = '';
        document.getElementById('create-password-confirm').value = '';

        openClipboardPage(result.clipboardCode, passwordHash);
    } catch (err) {
        ui.showError('home-error', err.message);
    }
}

// --- Open existing clipboard ---

async function handleOpen() {
    const code = document.getElementById('open-code').value.trim();
    const password = document.getElementById('open-password').value;

    if (!code) {
        ui.showError('home-error', 'Please enter a clipboard ID');
        return;
    }
    if (!password) {
        ui.showError('home-error', 'Please enter the password');
        return;
    }

    try {
        const handshake = await api.getHandshake(code);

        if (handshake.status !== 'ACTIVE') {
            ui.showError('home-error', 'Clipboard is no longer active');
            return;
        }

        // Compute password hash using server salt (same derivation as during creation)
        const passwordHash = await crypto.computePasswordHash(password, handshake.salt);

        const valid = await api.verifyPassword(code, passwordHash);
        if (!valid) {
            ui.showError('home-error', 'Invalid password');
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

        document.getElementById('open-code').value = '';
        document.getElementById('open-password').value = '';

        openClipboardPage(code, passwordHash);
    } catch (err) {
        ui.showError('home-error', err.message);
    }
}

// --- Open clipboard page and connect ---

async function openClipboardPage(clipboardCode, passwordHash) {
    const state = getState();

    ui.showPage('clipboard-page');
    ui.setClipboardId(clipboardCode);
    setState({ currentPage: 'clipboard' });

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
        // Refresh file list to update source online status
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
            // Just update version
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
        const file = msg.file;
        state.files.push(file);
        await ui.renderFileList(state.files);
    });

    ws.on('file_removed', (msg) => {
        const state = getState();
        state.files = state.files.filter(f => f.fileCode !== msg.fileCode);
        ui.renderFileList(state.files);
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
        goHome();
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

    // Debounce
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

async function handleFilesSelected(event) {
    const files = event.target.files;
    if (!files || files.length === 0) return;

    const state = getState();
    for (const file of files) {
        try {
            const totalChunks = Math.ceil(file.size / CHUNK_SIZE);

            // Encrypt file name and mime type
            const encName = await crypto.encryptString(state.cryptoKey, file.name);
            const encMime = file.type
                ? await crypto.encryptString(state.cryptoKey, file.type)
                : { ciphertext: '', iv: '' };

            // Register file on server
            const manifest = await api.addFile(state.clipboardCode, {
                encryptedName: encName.ciphertext,
                encryptedNameIv: encName.iv,
                encryptedMime: encMime.ciphertext,
                encryptedMimeIv: encMime.iv,
                sizeBytes: file.size,
                totalChunks,
                sourceDeviceId: state.deviceId
            });

            // Store file locally in IndexedDB
            await fileStore.storeFile(manifest.fileCode, file);

            // Update local state
            state.files.push(manifest);
            await ui.renderFileList(state.files);

            // Notify other devices via WebSocket
            ws.send({
                type: 'file_added',
                file: manifest
            });
        } catch (err) {
            ui.showError('clipboard-error', 'Failed to add file: ' + err.message);
        }
    }

    // Reset file input
    event.target.value = '';
}

function handleFileAction(event) {
    const btn = event.target.closest('button[data-action]');
    if (!btn) return;

    const action = btn.dataset.action;
    const fileCode = btn.dataset.fileCode;

    if (action === 'download') {
        startDownload(fileCode);
    } else if (action === 'remove') {
        removeFile(fileCode);
    }
}

function handleTransferAction(event) {
    const btn = event.target.closest('button[data-action]');
    if (!btn) return;

    if (btn.dataset.action === 'cancel-transfer') {
        const transferCode = btn.dataset.transferCode;
        ws.send({ type: 'transfer_cancel', transferCode });
    }
}

async function removeFile(fileCode) {
    const state = getState();
    try {
        await api.removeFile(state.clipboardCode, fileCode);
        await fileStore.removeFile(fileCode);
        state.files = state.files.filter(f => f.fileCode !== fileCode);
        await ui.renderFileList(state.files);
        ws.send({ type: 'file_removed', fileCode });
    } catch (err) {
        ui.showError('clipboard-error', 'Failed to remove file: ' + err.message);
    }
}

// --- File download (target device) ---

async function startDownload(fileCode) {
    const state = getState();
    const file = state.files.find(f => f.fileCode === fileCode);
    if (!file) return;

    try {
        const transfer = await api.createTransfer(state.clipboardCode, fileCode, state.deviceId);

        // Track transfer
        let fileName = fileCode;
        try {
            fileName = await crypto.decryptString(state.cryptoKey, file.encryptedName, file.encryptedNameIv);
        } catch { /* fallback */ }

        state.activeTransfers.set(transfer.transferCode, {
            ...transfer,
            fileName,
            direction: 'download',
            currentChunk: 0,
            chunks: [],
        });
        ui.renderTransfers(state.activeTransfers);

        // Request first chunk via WebSocket
        ws.send({
            type: 'transfer_request',
            transferCode: transfer.transferCode,
            fileCode,
            sourceDeviceId: file.sourceDeviceId,
            chunkNo: 0
        });
    } catch (err) {
        ui.showError('clipboard-error', 'Failed to start download: ' + err.message);
    }
}

// --- File upload (source device - responding to chunk requests) ---

async function handleSendChunkRequest(msg) {
    const state = getState();
    const { transferCode, fileCode, chunkNo } = msg;

    try {
        // Read chunk from local file
        const file = state.files.find(f => f.fileCode === fileCode);
        const chunkSize = file ? file.chunkSize : CHUNK_SIZE;
        const chunkData = await fileStore.readChunk(fileCode, chunkNo, chunkSize);

        // Encrypt chunk
        const encrypted = await crypto.encryptChunk(state.cryptoKey, chunkData);

        // Upload to relay
        await api.uploadChunk(transferCode, chunkNo, encrypted.data, encrypted.iv);

        // Get transfer info to find target device
        const transfer = await api.getTransfer(transferCode);

        // Notify target that chunk is ready
        ws.send({
            type: 'chunk_uploaded',
            transferCode,
            chunkNo,
            targetDeviceId: transfer.targetDeviceId
        });
    } catch (err) {
        console.error('Failed to send chunk:', err);
        ui.showError('clipboard-error', 'Failed to send file chunk: ' + err.message);
    }
}

// --- Chunk ready (target device receives notification) ---

async function handleChunkReady(msg) {
    const state = getState();
    const { transferCode, chunkNo } = msg;
    const transfer = state.activeTransfers.get(transferCode);
    if (!transfer) return;

    try {
        // Download chunk from relay
        const chunk = await api.downloadChunk(transferCode, chunkNo);

        // Decrypt chunk
        const decrypted = await crypto.decryptChunk(state.cryptoKey, chunk.data, chunk.iv);
        transfer.chunks.push(new Uint8Array(decrypted));
        transfer.currentChunk = chunkNo + 1;
        transfer.status = `${chunkNo + 1}/${transfer.totalChunks}`;
        ui.renderTransfers(state.activeTransfers);

        // Acknowledge chunk — server will request next or complete
        ws.send({ type: 'chunk_ack', transferCode });
    } catch (err) {
        console.error('Failed to download chunk:', err);
        transfer.status = 'Error: ' + err.message;
        ui.renderTransfers(state.activeTransfers);
    }
}

// --- Transfer complete ---

async function handleTransferComplete(msg) {
    const state = getState();
    const transfer = state.activeTransfers.get(msg.transferCode);
    if (!transfer || transfer.direction !== 'download') {
        state.activeTransfers.delete(msg.transferCode);
        ui.renderTransfers(state.activeTransfers);
        return;
    }

    try {
        // Reconstruct file from chunks
        const blob = new Blob(transfer.chunks);

        // Trigger browser download
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = transfer.fileName || 'download';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);

        ui.showInfo(`File "${transfer.fileName}" downloaded successfully`);
    } catch (err) {
        ui.showError('clipboard-error', 'Failed to reconstruct file: ' + err.message);
    }

    state.activeTransfers.delete(msg.transferCode);
    ui.renderTransfers(state.activeTransfers);
}

function handleTransferCancelled(msg) {
    const state = getState();
    state.activeTransfers.delete(msg.transferCode);
    ui.renderTransfers(state.activeTransfers);
    ui.showInfo('Transfer cancelled');
}

// --- Refresh file list with updated source status ---

async function refreshFileList() {
    const state = getState();
    if (!state.clipboardCode) return;
    try {
        const clipState = await api.getClipboardState(state.clipboardCode);
        setState({ files: clipState.files });
        await ui.renderFileList(clipState.files);
    } catch { /* ignore refresh errors */ }
}

// --- Delete clipboard ---

async function handleDelete() {
    if (!confirm('Are you sure you want to permanently delete this clipboard?')) return;

    const state = getState();
    try {
        ws.send({ type: 'delete_clipboard' });
    } catch (err) {
        ui.showError('clipboard-error', 'Failed to delete clipboard: ' + err.message);
    }
}

// --- Navigation ---

function handleBack() {
    if (ws) ws.disconnect();
    goHome();
}

function goHome() {
    resetState();
    setState({ deviceId: generateDeviceId() });
    fileStore.clearAll();
    ui.setTextContent('');
    ui.setTextVersion(0);
    ui.setConnectionStatus(false);
    ui.setDeviceCount(0);
    ui.hideDeletedOverlay();
    ui.showPage('home-page');
}
