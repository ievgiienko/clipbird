/**
 * PWA install prompt handling and platform-aware UI.
 */

let deferredPrompt = null;
let isInstalled = false;

/**
 * Initialize PWA support: register service worker, set up install prompt,
 * and configure platform-aware install UI on the landing page.
 */
export function initPwa() {
  registerServiceWorker();
  detectInstallState();
  listenForInstallPrompt();
  setupInstallButton();
}

// --- Service worker registration ---

function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return;

  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/service-worker.js', { scope: '/' })
      .catch(err => console.warn('SW registration failed:', err));
  });
}

// --- Detect if already installed ---

function detectInstallState() {
  // Check if running in standalone mode (installed PWA)
  if (window.matchMedia('(display-mode: standalone)').matches ||
      window.navigator.standalone === true) {
    isInstalled = true;
  }

  // Listen for display mode changes
  window.matchMedia('(display-mode: standalone)').addEventListener('change', (e) => {
    if (e.matches) {
      isInstalled = true;
      updateInstallUI();
    }
  });
}

// --- Install prompt ---

function listenForInstallPrompt() {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    updateInstallUI();
  });

  window.addEventListener('appinstalled', () => {
    isInstalled = true;
    deferredPrompt = null;
    updateInstallUI();
  });
}

// --- Install button logic ---

function setupInstallButton() {
  const installBtn = document.getElementById('pwa-install-btn');
  const iosModal = document.getElementById('ios-install-modal');
  if (!installBtn) return;

  installBtn.addEventListener('click', handleInstallClick);

  // iOS modal close
  if (iosModal) {
    const closeBtn = document.getElementById('btn-close-ios-install');
    if (closeBtn) closeBtn.addEventListener('click', () => iosModal.style.display = 'none');
    iosModal.addEventListener('click', (e) => {
      if (e.target === e.currentTarget) iosModal.style.display = 'none';
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && iosModal.style.display !== 'none') {
        iosModal.style.display = 'none';
      }
    });
  }

  // Initial UI state
  updateInstallUI();
}

async function handleInstallClick() {
  if (isInstalled) return;

  if (deferredPrompt) {
    // Chrome / Edge / supported browsers — trigger native prompt
    deferredPrompt.prompt();
    const { outcome } = await deferredPrompt.userChoice;
    if (outcome === 'accepted') {
      isInstalled = true;
    }
    deferredPrompt = null;
    updateInstallUI();
  } else if (isIos()) {
    // iOS — show instructions modal
    const modal = document.getElementById('ios-install-modal');
    if (modal) modal.style.display = 'flex';
  }
  // On unsupported browsers the button won't be visible
}

// --- Platform detection ---

function isIos() {
  return /iPad|iPhone|iPod/.test(navigator.userAgent) ||
         (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
}

// --- UI updates ---

function updateInstallUI() {
  const section = document.getElementById('pwa-install-section');
  const installBtn = document.getElementById('pwa-install-btn');
  const installHint = document.getElementById('pwa-install-hint');
  if (!section || !installBtn) return;

  if (isInstalled) {
    // Already installed
    installBtn.textContent = 'App Installed';
    installBtn.disabled = true;
    installBtn.classList.add('installed');
    if (installHint) installHint.textContent = 'ClipBird is installed on this device';
    return;
  }

  if (deferredPrompt) {
    // Native install available (Chrome, Edge, etc.)
    section.style.display = '';
    installBtn.textContent = 'Install ClipBird';
    installBtn.disabled = false;
    if (installHint) installHint.textContent = 'Install for faster access on your device';
  } else if (isIos()) {
    // iOS — show button that opens instructions
    section.style.display = '';
    installBtn.textContent = 'Install on iPhone / iPad';
    installBtn.disabled = false;
    if (installHint) installHint.textContent = 'Add ClipBird to your Home Screen';
  } else {
    // Unsupported or already in standalone — hide section
    section.style.display = 'none';
  }
}
