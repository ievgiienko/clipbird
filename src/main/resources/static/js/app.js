/**
 * Main application entry point.
 * Orchestrates all modules: crypto, api, ws, file-store, state, ui, router, qr.
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

const CHUNK_SIZE = 5 * 1024 * 1024; // 5 MB
const TEXT_DEBOUNCE_MS = 500;

let ws = null;

// --- Initialization ---

document.addEventListener('DOMContentLoaded', () => {
    setState({ deviceId: generateDeviceId() });
    bindEvents();
    passwordStrength.bind('create-password', 'password-strength');
    router.init(onRouteChange);
});

function bindEvents() {
    // Create page
    document.getElementById('btn-create').addEventListener('click', handleCreate);
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

    // QR modal
    document.getElementById('btn-close-qr').addEventListener('click', ui.hideQrModal);
    document.getElementById('btn-qr-copy').addEventListener('click', () => {
        const state = getState();
        ui.copyToClipboard(ui.getClipboardUrl(state.clipboardCode));
    });
    document.getElementById('qr-modal').addEventListener('click', (e) => {
        if (e.target === e.currentTarget) ui.hideQrModal();
    });

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
        if (state.clipboardCode === route.code && state.cryptoKey) {
            // Already authenticated for this clipboard
            ui.showUnlockedState(route.code);
        } else {
            // Need password
            ui.showLockedState(route.code);
        }
    }

    if (route.page === 'create') {
        document.getElementById('create-password').value = '';
        document.getElementById('create-password-confirm').value = '';
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

            const manifest = await api.addFile(state.clipboardCode, {
                encryptedName: encName.ciphertext,
                encryptedNameIv: encName.iv,
                encryptedMime: encMime.ciphertext,
                encryptedMimeIv: encMime.iv,
                sizeBytes: file.size,
                totalChunks,
                sourceDeviceId: state.deviceId
            });

            await fileStore.storeFile(manifest.fileCode, file);

            state.files.push(manifest);
            await ui.renderFileList(state.files);

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

// --- File upload (source device) ---

async function handleSendChunkRequest(msg) {
    const state = getState();
    const { transferCode, fileCode, chunkNo } = msg;

    try {
        const file = state.files.find(f => f.fileCode === fileCode);
        const chunkSize = file ? file.chunkSize : CHUNK_SIZE;
        const chunkData = await fileStore.readChunk(fileCode, chunkNo, chunkSize);

        const encrypted = await crypto.encryptChunk(state.cryptoKey, chunkData);
        await api.uploadChunk(transferCode, chunkNo, encrypted.data, encrypted.iv);

        const transfer = await api.getTransfer(transferCode);

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

// --- Chunk ready (target device) ---

async function handleChunkReady(msg) {
    const state = getState();
    const { transferCode, chunkNo } = msg;
    const transfer = state.activeTransfers.get(transferCode);
    if (!transfer) return;

    try {
        const chunk = await api.downloadChunk(transferCode, chunkNo);
        const decrypted = await crypto.decryptChunk(state.cryptoKey, chunk.data, chunk.iv);
        transfer.chunks.push(new Uint8Array(decrypted));
        transfer.currentChunk = chunkNo + 1;
        transfer.status = `${chunkNo + 1}/${transfer.totalChunks}`;
        ui.renderTransfers(state.activeTransfers);

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
        const blob = new Blob(transfer.chunks);
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

// --- Refresh file list ---

async function refreshFileList() {
    const state = getState();
    if (!state.clipboardCode) return;
    try {
        const clipState = await api.getClipboardState(state.clipboardCode);
        setState({ files: clipState.files });
        await ui.renderFileList(clipState.files);
    } catch { /* ignore refresh errors */ }
}

// --- Copy link ---

function handleCopyLink() {
    const state = getState();
    ui.copyToClipboard(ui.getClipboardUrl(state.clipboardCode));
}

// --- QR code ---

function handleShowQr() {
    const state = getState();
    const url = ui.getClipboardUrl(state.clipboardCode);
    try {
        const matrix = generateQR(url);
        const svg = toSvg(matrix, 6);
        ui.showQrModal(svg, url);
    } catch (err) {
        ui.showError('clipboard-error', 'Failed to generate QR code: ' + err.message);
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
