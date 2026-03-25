const STORAGE_KEY = 'theme';

function getPreferred() {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
    return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function apply(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem(STORAGE_KEY, theme);
    updateToggle(theme);
}

function updateToggle(theme) {
    const toggle = document.getElementById('theme-toggle');
    if (!toggle) return;
    toggle.querySelectorAll('.theme-toggle-option').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.themeValue === theme);
    });
}

export function initTheme() {
    const theme = getPreferred();
    apply(theme);

    const toggle = document.getElementById('theme-toggle');
    if (toggle) {
        toggle.addEventListener('click', (e) => {
            const btn = e.target.closest('.theme-toggle-option');
            if (!btn) return;
            apply(btn.dataset.themeValue);
        });
    }
}
