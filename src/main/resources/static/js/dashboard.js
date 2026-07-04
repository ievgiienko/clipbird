/**
 * Dashboard: lists the authenticated user's clipboards with pagination.
 * Renders into the static elements already present in #dashboard-page.
 */

import {showDeleteModal} from "./ui.js";

let currentPage = 0;
let totalPages = 0;
let userEmail = '';

// Public API

export async function show(user) {
    currentPage = 0;
    totalPages = 0;
    userEmail = user?.email ?? '';
    const emailEl = document.getElementById('dashboard-email');
    if (emailEl) emailEl.textContent = userEmail;

    await loadPage(0);

    document.getElementById('btn-dashboard-logout')?.addEventListener('click', async () => {
        await fetch('/api/auth/logout', {method: 'POST', credentials: 'include'});
        window.location.reload();
    });
}

// Data loading

async function loadPage(page) {
    const listEl = document.getElementById('dashboard-list');
    const pageEl = document.getElementById('dashboard-pagination');
    if (!listEl) return;

    listEl.innerHTML = '<div class="loading-state">Loading…</div>';
    if (pageEl) pageEl.style.display = 'none';

    const pageSize = 20;
    try {
        const res = await fetch(`/api/users/me/clipboards?page=${page}&size=${pageSize}`, {credentials: 'include'});
        if (!res.ok) {
            listEl.innerHTML = '<div class="loading-state">Failed to load clipboards.</div>';
            return;
        }

        const data = await res.json();
        currentPage = page;
        totalPages = data.totalPages;

        renderList(listEl, data.items);
        if (totalPages > 1 && pageEl) {
            renderPagination(pageEl, data.totalPages, page, data.totalElements, pageSize);
            pageEl.style.display = '';
        }
    } catch {
        listEl.innerHTML = '<div class="loading-state">Network error. Please try again.</div>';
    }
}

// Rendering

function renderList(container, items) {
    if (!items || items.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state-icon">
                    <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                </div>
                <p>No clipboards yet.</p>
                <a href="/create" data-link class="btn btn-primary">Create your first clipboard</a>
            </div>`;
        return;
    }

    container.innerHTML = items.map(item => clipboardCard(item)).join('');

    container.querySelectorAll('[data-delete-code]').forEach(btn => {
        btn.addEventListener('click', () => handleDelete(btn.dataset.deleteCode));
    });
}

function clipboardCard(item) {
    const statusClass = item.status.toLowerCase();
    const created = formatDate(item.createdAt);
    const expires = formatDate(item.expiresAt);
    const isActive = item.status === 'ACTIVE';

    return `
        <div class="clipboard-card" data-code="${esc(item.clipboardCode)}">
            <div class="clipboard-card-main">
                <a href="/c/${esc(item.clipboardCode)}" data-link class="clipboard-card-code">${esc(item.clipboardCode)}</a>
                <div class="clipboard-card-meta">
                    <span class="status-badge ${statusClass}">${esc(item.status)}</span>
                    <span class="clipboard-card-date">Created ${created}</span>
                    <span class="clipboard-card-date">Expires ${expires}</span>
                </div>
            </div>
            <div class="clipboard-card-actions">
                ${isActive ? `<a href="/c/${esc(item.clipboardCode)}" data-link class="btn btn-secondary btn-small">Open</a>` : ''}
                <button class="btn btn-danger btn-small" data-delete-code="${esc(item.clipboardCode)}"
                    title="Delete clipboard">Delete</button>
            </div>
        </div>`;
}

function renderPagination(container, total, current, totalElements, size) {
    const start = current * size + 1;
    const end = Math.min((current + 1) * size, totalElements);

    let html = `<span class="pagination-info">${start}–${end} of ${totalElements}</span>`;
    html += `<button ${current === 0 ? 'disabled' : ''} data-pg="${current - 1}">&larr; Prev</button>`;

    const lo = Math.max(0, current - 2);
    const hi = Math.min(total - 1, current + 2);
    for (let i = lo; i <= hi; i++) {
        html += `<button ${i === current ? 'class="active"' : ''} data-pg="${i}">${i + 1}</button>`;
    }

    html += `<button ${current >= total - 1 ? 'disabled' : ''} data-pg="${current + 1}">Next &rarr;</button>`;
    container.innerHTML = html;

    container.querySelectorAll('button[data-pg]').forEach(btn => {
        btn.addEventListener('click', () => loadPage(parseInt(btn.dataset.pg, 10)));
    });
}

// Actions


async function handleDelete(code) {
    showDeleteModal(async () => {
        try {
            const res = await fetch(
                `/api/clipboards/${encodeURIComponent(code)}`,
                {
                    method: "DELETE",
                    credentials: "include"
                }
            );

            if (!res.ok && res.status !== 204) {
                throw new Error("Delete failed");
            }

            document.querySelector(`[data-code="${esc(code)}"]`)?.remove();
            await loadPage(currentPage);

        } catch (err) {

            console.error(err);
        }
    });
}


// Helpers

function formatDate(isoString) {
    if (!isoString) return '—';
    const d = new Date(isoString);
    return d.toLocaleDateString(undefined, {month: 'short', day: 'numeric', year: 'numeric'});
}

function esc(str) {
    return String(str ?? '').replace(/[&<>"']/g, c =>
        ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'})[c]);
}
