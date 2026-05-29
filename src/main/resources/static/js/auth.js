/**
 * Account auth: login, register, logout, current-user check.
 * Form events are bound lazily when the page becomes visible.
 */

// API helpers

export async function getCurrentUser() {
    try {
        const res = await fetch('/api/auth/me');
        if (!res.ok || res.status === 204) return null;
        return res.json();
    } catch {
        return null;
    }
}

async function apiLogin(email, password) {
    const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
    });
    if (!res.ok) throw new Error('Invalid email or password');
    return res.json();
}

async function apiRegister(email, password) {
    const res = await fetch('/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        const msg = err.message || (res.status === 409 ? 'Email already registered' : 'Registration failed');
        throw new Error(msg);
    }
    return res.json();
}

export async function logout() {
    await fetch('/api/auth/logout', { method: 'POST' });
}

// Nav auth state

export async function initNav(navigateFn) {
    const user = await getCurrentUser();
    const guestNav = document.getElementById('nav-guest');
    const userNav  = document.getElementById('nav-user');
    const emailEl  = document.getElementById('nav-user-email');

    if (user) {
        if (guestNav) guestNav.style.display = 'none';
        if (userNav)  userNav.style.display  = '';
        if (emailEl)  emailEl.textContent    = user.email;
    } else {
        if (guestNav) guestNav.style.display = '';
        if (userNav)  userNav.style.display  = 'none';
    }

    document.getElementById('btn-nav-logout')?.addEventListener('click', async () => {
        await logout();
        window.location.reload();
    });
}

// Login page

export function initLoginPage(navigateFn) {
    const btn       = document.getElementById('btn-login');
    const emailEl   = document.getElementById('login-email');
    const passwordEl = document.getElementById('login-password');
    const errorEl   = document.getElementById('login-error');

    if (!btn) return;

    const doLogin = async () => {
        const email    = emailEl.value.trim();
        const password = passwordEl.value;

        hideError(errorEl);

        if (!email) { showError(errorEl, 'Please enter your email'); return; }
        if (!password) { showError(errorEl, 'Please enter your password'); return; }

        btn.disabled = true;
        try {
            await apiLogin(email, password);
            await initNav(navigateFn);
            navigateFn('/dashboard');
        } catch (err) {
            showError(errorEl, err.message);
        } finally {
            btn.disabled = false;
        }
    };

    btn.addEventListener('click', doLogin);
    passwordEl.addEventListener('keydown', e => { if (e.key === 'Enter') doLogin(); });
    emailEl.addEventListener('keydown', e => { if (e.key === 'Enter') passwordEl.focus(); });
}

// Register page

export function initRegisterPage(navigateFn) {
    const btn            = document.getElementById('btn-register');
    const emailEl        = document.getElementById('reg-email');
    const passwordEl     = document.getElementById('reg-password');
    const confirmEl      = document.getElementById('reg-password-confirm');
    const errorEl        = document.getElementById('register-error');

    if (!btn) return;

    const doRegister = async () => {
        const email    = emailEl.value.trim();
        const password = passwordEl.value;
        const confirm  = confirmEl.value;

        hideError(errorEl);

        if (!email)    { showError(errorEl, 'Please enter your email'); return; }
        if (!email.includes('@')) { showError(errorEl, 'Please enter a valid email'); return; }
        if (password.length < 8) { showError(errorEl, 'Password must be at least 8 characters'); return; }
        if (password !== confirm) { showError(errorEl, 'Passwords do not match'); return; }

        btn.disabled = true;
        try {
            await apiRegister(email, password);
            // Auto-login after registration
            await apiLogin(email, password);
            await initNav(navigateFn);
            navigateFn('/dashboard');
        } catch (err) {
            showError(errorEl, err.message);
        } finally {
            btn.disabled = false;
        }
    };

    btn.addEventListener('click', doRegister);
    confirmEl.addEventListener('keydown', e => { if (e.key === 'Enter') doRegister(); });
}

// Utilities

function showError(el, msg) {
    if (!el) return;
    el.textContent = msg;
    el.style.display = '';
}

function hideError(el) {
    if (!el) return;
    el.style.display = 'none';
    el.textContent = '';
}
