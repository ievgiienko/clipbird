/**
 * UI manipulation helpers.
 */

import {getState} from './state.js';
import {decryptString, decryptChunkBinary, base64ToBytes} from './crypto.js';

// --- Page navigation ---

export function showPage(pageId) {
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    const page = document.getElementById(pageId);
    if (page) page.classList.add('active');
}

// --- Clipboard locked/unlocked states ---

export function showLockedState(clipboardCode) {
    document.getElementById('clipboard-locked').style.display = 'block';
    document.getElementById('clipboard-unlocked').style.display = 'none';
    document.getElementById('locked-clipboard-id').textContent = clipboardCode;
    document.getElementById('url-password').value = '';
    document.getElementById('url-password').focus();
}

export function showUnlockedState(clipboardCode) {
    document.getElementById('clipboard-locked').style.display = 'none';
    document.getElementById('clipboard-unlocked').style.display = 'block';
    setClipboardId(clipboardCode);
}

// --- Status indicators ---

export function setConnectionStatus(connected) {
    const el = document.getElementById('connection-status');
    el.textContent = connected ? 'Connected' : 'Disconnected';
    el.className = 'status ' + (connected ? 'connected' : 'disconnected');
}

export function setDeviceCount(count) {
    document.getElementById('device-count').textContent = `${count} device${count !== 1 ? 's' : ''}`;
}

export function setClipboardId(code) {
    document.getElementById('clipboard-id').textContent = code;
}

export function getClipboardUrl(code) {
    return `${location.origin}/c/${code}`;
}

export function setTextVersion(version) {
    document.getElementById('text-version').textContent = `Version: ${version}`;
}

export function setTextSize(bytes) {
    const size = bytes < 1024 ? `${bytes} B` :
        bytes < 1048576 ? `${(bytes / 1024).toFixed(1)} KB` :
            `${(bytes / 1048576).toFixed(1)} MB`;
    document.getElementById('text-size').textContent = size;
}

// --- Error/Info display ---

export function showError(elementId, message) {
    const el = document.getElementById(elementId);
    if (!el) return;
    el.textContent = message;
    el.style.display = 'block';
    setTimeout(() => {
        el.style.display = 'none';
    }, 8000);
}

export function showInfo(message) {
    const el = document.getElementById('clipboard-info');
    el.textContent = message;
    el.style.display = 'block';
    setTimeout(() => {
        el.style.display = 'none';
    }, 5000);
}

export function hideError(elementId) {
    const el = document.getElementById(elementId);
    if (el) el.style.display = 'none';
}

// --- Copy to clipboard ---

export async function copyToClipboard(text) {
    try {
        await navigator.clipboard.writeText(text);
        showCopyFeedback();
        return true;
    } catch {
        // Fallback for older browsers
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.cssText = 'position:fixed;left:-9999px';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
        showCopyFeedback();
        return true;
    }
}

function showCopyFeedback() {
    const existing = document.querySelector('.copy-feedback');
    if (existing) existing.remove();

    const el = document.createElement('div');
    el.className = 'copy-feedback';
    el.textContent = 'Copied to clipboard';
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 2000);
}

// --- QR Modal ---

export function showQrCode(svgHtml, displayUrl) {
    document.getElementById('qr-code').innerHTML = svgHtml;
    document.getElementById('qr-url').textContent = displayUrl;
}

export function showQrModal(svgHtml, url) {
    showQrCode(svgHtml, url);
    document.getElementById('qr-modal').style.display = 'flex';
}

export function hideQrModal() {
    document.getElementById('qr-modal').style.display = 'none';
}

// --- Delete confirmation modal ---
let confirmDeleteCallback = null;

export function showDeleteModal(callback) {
    confirmDeleteCallback = callback;
    document.getElementById('confirm-delete-modal').style.display = 'flex';
}

export function hideDeleteModal() {
    document.getElementById("confirm-delete-modal").style.display = "none";
}

document.getElementById("btn-confirm-delete")?.addEventListener("click", () => {
    hideDeleteModal();

    if (confirmDeleteCallback) {
        confirmDeleteCallback();
        confirmDeleteCallback = null;
    }
});

document.getElementById("btn-cancel-delete")?.addEventListener("click", () => {
    hideDeleteModal();
    confirmDeleteCallback = null;
});

document.getElementById("btn-close-delete")?.addEventListener("click", () => {
    hideDeleteModal();
    confirmDeleteCallback = null;
});


// --- Text area ---

export function getTextContent() {
    return document.getElementById('clipboard-text').value;
}

export function setTextContent(text) {
    document.getElementById('clipboard-text').value = text;
    setTextSize(new TextEncoder().encode(text).length);
}

export function clear() {
    let elCLear = document.getElementById('clipboard-text');
    elCLear.value = "";
}

// --- Drop zone ---

export function setDropZoneActive(active) {
    const dz = document.getElementById('drop-zone');
    if (active) {
        dz.classList.add('drag-over');
    } else {
        dz.classList.remove('drag-over');
    }
}

export function setDropZoneEnabled(enabled) {
    const dz = document.getElementById('drop-zone');
    if (enabled) {
        dz.classList.remove('disabled');
    } else {
        dz.classList.add('disabled');
    }
}

// --- Download All button ---

export function renderDownloadAllButton(downloadableCount, batchState) {
    const container = document.getElementById('download-all-container');
    if (!container) return;

    if (downloadableCount < 2) {
        container.innerHTML = '';
        return;
    }

    const area = document.createElement('div');
    area.className = 'download-all-area';

    if (batchState && batchState.active) {
        const statusText = batchState.failed > 0
            ? `${batchState.completed} completed, ${batchState.failed} failed`
            : `Downloading ${batchState.completed + 1} of ${batchState.total}...`;
        const statusClass = batchState.failed > 0 ? 'download-all-status error' : 'download-all-status';
        area.innerHTML = `
            <span class="${statusClass}">${escapeHtml(statusText)}</span>
            <button class="btn btn-small btn-danger" id="btn-cancel-download-all">Cancel</button>
        `;
    } else {
        area.innerHTML = `
            <button class="btn btn-small btn-primary" id="btn-download-all">Download All (${downloadableCount})</button>
        `;
    }

    container.innerHTML = '';
    container.appendChild(area);
}

// --- File list ---

export async function renderFileList(files) {
    const container = document.getElementById('file-list');
    const state = getState();

    if (!files || files.length === 0) {
        container.innerHTML = '';
        return;
    }

    container.innerHTML = '';
    for (const file of files) {
        const el = document.createElement('div');
        el.className = 'file-item';
        el.dataset.fileCode = file.fileCode;

        let fileName = file.fileCode;
        try {
            if (state.cryptoKey && file.encryptedName) {
                fileName = await decryptString(state.cryptoKey, file.encryptedName, file.encryptedNameIv);
            }
        } catch { /* use fileCode as fallback */
        }

        const isOwn = file.sourceDeviceId === state.deviceId;
        const sizeStr = formatBytes(file.sizeBytes);
        const sourceStatus = isOwn ? ''
            : file.sourceOnline
                ? '<span class="file-source-status online">Source online</span>'
                : '<span class="file-source-status offline">Source offline</span>';
        const thumbHtml = file.encryptedPreview
            ? `<img class="file-thumb" alt="">`
            : '';

        el.innerHTML = `
            ${thumbHtml}
            <div class="file-info">
                <div class="file-name" title="${escapeHtml(fileName)}">${escapeHtml(fileName)}</div>
                <div class="file-meta">
                    <span>${sizeStr}</span>
                    <span>${file.totalChunks} chunk${file.totalChunks !== 1 ? 's' : ''}</span>
                    ${sourceStatus}
                </div>
            </div>
            <div class="file-actions">
                ${!isOwn && file.sourceOnline ? `<button class="btn btn-small btn-primary" data-action="download" data-file-code="${file.fileCode}">Download</button>` : ''}
                ${!isOwn && !file.sourceOnline ? `<button class="btn btn-small btn-secondary" disabled>Unavailable</button>` : ''}
                ${isOwn ? `<button class="btn btn-small btn-danger" data-action="remove" data-file-code="${file.fileCode}">Remove</button>` : ''}
            </div>
        `;
        container.appendChild(el);

        if (file.encryptedPreview && state.cryptoKey) {
            void (async () => {
                try {
                    const plain = await decryptChunkBinary(state.cryptoKey, base64ToBytes(file.encryptedPreview), base64ToBytes(file.encryptedPreviewIv));
                    const blob = new Blob([plain], {type: 'image/jpeg'});
                    const url = URL.createObjectURL(blob);
                    const imgEl = el.querySelector('.file-thumb');
                    if (imgEl && document.contains(el)) {
                        imgEl.src = url;
                        imgEl.onload = () => URL.revokeObjectURL(url);
                    } else {
                        URL.revokeObjectURL(url);
                    }
                } catch { /* ignore decryption errors */
                }
            })();
        }
    }
}

// --- Transfer list ---

export function renderTransfers(transfers) {
    const section = document.getElementById('transfer-section');
    const container = document.getElementById('transfer-list');

    if (transfers.size === 0) {
        section.style.display = 'none';
        return;
    }

    section.style.display = 'block';
    container.innerHTML = '';

    for (const [code, transfer] of transfers) {
        const el = document.createElement('div');
        el.className = 'transfer-item';
        const progress = transfer.totalChunks > 0
            ? Math.round((transfer.currentChunk / transfer.totalChunks) * 100)
            : 0;
        const direction = transfer.direction === 'upload' ? 'Sending' : 'Receiving';

        el.innerHTML = `
            <div>${direction}: ${escapeHtml(transfer.fileName || transfer.fileCode)}</div>
            <div style="font-size:0.8rem;color:var(--text-muted);margin-top:0.25rem">
                Chunk ${transfer.currentChunk}/${transfer.totalChunks}
                <button class="btn btn-small btn-danger" data-action="cancel-transfer" data-transfer-code="${code}" style="margin-left:0.5rem">Cancel</button>
            </div>
            <div class="transfer-progress">
                <div class="transfer-progress-bar" style="width:${progress}%"></div>
            </div>
        `;
        container.appendChild(el);
    }
}

// --- Overlay ---

export function showDeletedOverlay() {
    document.getElementById('deleted-overlay').style.display = 'flex';
}

export function hideDeletedOverlay() {
    document.getElementById('deleted-overlay').style.display = 'none';
}

// --- Helpers ---

function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}
