/**
 * Simple SPA router using the History API.
 * Routes: /, /create, /open, /c/{clipboardCode}, /login, /register, /dashboard
 */

let onRouteChange = null;

export function init(callback) {
    onRouteChange = callback;
    window.addEventListener('popstate', () => handleRoute());

    // Intercept clicks on links with data-link attribute
    document.addEventListener('click', (e) => {
        const a = e.target.closest('a[data-link]');
        if (a) {
            e.preventDefault();
            navigate(a.getAttribute('href'));
        }
    });

    handleRoute();
}

export function navigate(path) {
    history.pushState(null, '', path);
    handleRoute();
}

export function getRoute() {
    return parseRoute(location.pathname);
}

function parseRoute(path) {
    if (path === '/create')    return { page: 'create' };
    if (path === '/open')      return { page: 'open' };
    if (path === '/login')     return { page: 'login' };
    if (path === '/register')  return { page: 'register' };
    if (path === '/dashboard') return { page: 'dashboard' };
    const clipMatch = path.match(/^\/c\/([A-Za-z0-9]+)$/);
    if (clipMatch) return { page: 'clipboard', code: clipMatch[1] };
    return { page: 'landing' };
}

function handleRoute() {
    const route = parseRoute(location.pathname);




    // Hide all pages
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));

    // Show target page
    const pageMap = {
        landing:   'landing-page',
        create:    'create-page',
        open:      'open-page',
        clipboard: 'clipboard-page',
        login:     'login-page',
        register:  'register-page',
        dashboard: 'dashboard-page',
    };
    const pageEl = document.getElementById(pageMap[route.page] || 'landing-page');
    if (pageEl) pageEl.classList.add('active');

    // Scroll to top on navigation
    window.scrollTo(0, 0);

    if (onRouteChange) onRouteChange(route);
}
