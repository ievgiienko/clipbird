/**
 * ClipBird Service Worker — Minimal PWA support.
 *
 * Strategy: Network-first for everything. This service worker exists primarily
 * to satisfy PWA installability requirements. It does NOT aggressively cache
 * dynamic clipboard data, API responses, or WebSocket traffic.
 *
 * Static shell assets are cached lightly on install for faster repeat loads,
 * but every request tries the network first. If the network is unavailable,
 * cached static assets are served as a fallback for the app shell only.
 *
 * Excluded from caching:
 *  - /api/*          (REST API — live clipboard data)
 *  - /ws/*           (WebSocket endpoints)
 *  - /c/*            (dynamic clipboard pages — data is fetched live)
 *  - Chrome extension requests
 */

const CACHE_NAME = 'clipbird-shell-v6';

const SHELL_ASSETS = [
  '/',
  '/index.html',
  '/css/styles.css',
  '/js/app.js',
  '/js/api.js',
  '/js/crypto.js',
  '/js/file-store.js',
  '/js/password-strength.js',
  '/js/pwa.js',
  '/js/qr.js',
  '/js/router.js',
  '/js/state.js',
  '/js/theme.js',
  '/js/ui.js',
  '/js/ws.js',
  '/img/favicon.svg',
  '/img/icon.svg',
  '/img/logo.svg',
  '/img/icon-192.svg',
  '/img/icon-512.svg',
  '/img/icon-maskable.svg',
  '/manifest.webmanifest'
];

// Patterns that must NEVER be served from cache
const NEVER_CACHE = [
  /^\/api\//,
  /^\/ws\//,
  /^\/c\//
];

function shouldNeverCache(url) {
  const path = new URL(url).pathname;
  return NEVER_CACHE.some(re => re.test(path));
}

// Install: pre-cache static shell
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(SHELL_ASSETS))
      .then(() => self.skipWaiting())
  );
});

// Activate: clean up old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys
          .filter(key => key !== CACHE_NAME)
          .map(key => caches.delete(key))
      ))
      .then(() => self.clients.claim())
  );
});

// Fetch: network-first for everything
self.addEventListener('fetch', (event) => {
  const { request } = event;

  // Skip non-GET requests
  if (request.method !== 'GET') return;

  // Never intercept dynamic/API routes — let them go straight to network
  if (shouldNeverCache(request.url)) return;

  // Skip chrome-extension and other non-http(s) schemes
  if (!request.url.startsWith('http')) return;

  event.respondWith(
    fetch(request)
      .then(response => {
        // Clone and update cache for shell assets on successful network response
        if (response.ok) {
          const responseClone = response.clone();
          caches.open(CACHE_NAME).then(cache => {
            cache.put(request, responseClone);
          });
        }
        return response;
      })
      .catch(() => {
        // Network failed — try cache fallback for shell assets only
        return caches.match(request).then(cached => {
          if (cached) return cached;
          // For navigation requests, serve the cached index.html (SPA)
          if (request.mode === 'navigate') {
            return caches.match('/index.html');
          }
          return new Response('Offline', { status: 503, statusText: 'Service Unavailable' });
        });
      })
  );
});
