/**
 * Application state management.
 */

const state = {
    // Auth
    clipboardCode: null,
    password: null,
    passwordHash: null,
    cryptoKey: null,
    salt: null,
    deviceId: null,

    // Clipboard data
    textVersion: 0,
    files: [],           // Array of FileManifestDto
    connectedDevices: 0,
    connected: false,

    // Transfers
    activeTransfers: new Map(),  // transferCode -> transfer info

    // UI
    currentPage: 'home',
    textUpdateTimer: null,
    isUpdatingText: false,
};

export function getState() {
    return state;
}

export function setState(updates) {
    Object.assign(state, updates);
}

export function resetState() {
    state.clipboardCode = null;
    state.password = null;
    state.passwordHash = null;
    state.cryptoKey = null;
    state.salt = null;
    state.textVersion = 0;
    state.files = [];
    state.connectedDevices = 0;
    state.connected = false;
    state.activeTransfers.clear();
    state.currentPage = 'home';
    if (state.textUpdateTimer) {
        clearTimeout(state.textUpdateTimer);
        state.textUpdateTimer = null;
    }
    state.isUpdatingText = false;
}

export function generateDeviceId() {
    let id = localStorage.getItem('deviceId');
    if (!id) {
        id = 'dev-' + crypto.randomUUID();
        localStorage.setItem('deviceId', id);
    }
    return id;
}
