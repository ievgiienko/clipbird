/**
 * Password strength evaluator and UI indicator.
 * Used only on the Create Clipboard page.
 */

const LEVELS = [
    { label: 'Very weak', cls: 'very-weak', segments: 1 },
    { label: 'Weak',      cls: 'weak',      segments: 2 },
    { label: 'Medium',    cls: 'medium',     segments: 3 },
    { label: 'Strong',    cls: 'strong',     segments: 4 },
];

/**
 * Evaluate password strength.
 * @param {string} password
 * @returns {{ score: number, level: object, suggestions: string[] }}
 */
export function evaluate(password) {
    const suggestions = [];
    let score = 0;

    if (!password) {
        return { score: 0, level: LEVELS[0], suggestions: [] };
    }

    // Length scoring
    if (password.length >= 6) score++;
    if (password.length >= 10) score++;
    if (password.length >= 14) score++;

    // Character variety
    if (/[A-Z]/.test(password)) {
        score++;
    } else {
        suggestions.push('Add an uppercase letter');
    }

    if (/[0-9]/.test(password)) {
        score++;
    } else {
        suggestions.push('Add a number');
    }

    if (/[^A-Za-z0-9]/.test(password)) {
        score++;
    } else {
        suggestions.push('Add a special character');
    }

    if (password.length < 6) {
        suggestions.unshift('Use at least 6 characters');
    } else if (password.length < 10) {
        suggestions.unshift('Use at least 10 characters for a strong password');
    }

    // Map score to level: 0-1 → very weak, 2-3 → weak, 4 → medium, 5+ → strong
    // Cap at medium if under 10 characters
    let levelIndex;
    if (score <= 1) levelIndex = 0;
    else if (score <= 3) levelIndex = 1;
    else if (score <= 4 || password.length < 10) levelIndex = 2;
    else levelIndex = 3;

    return { score, level: LEVELS[levelIndex], suggestions };
}

/**
 * Bind the strength indicator to a password input.
 * @param {string} inputId - ID of the password input element
 * @param {string} containerId - ID of the indicator container
 */
export function bind(inputId, containerId) {
    const input = document.getElementById(inputId);
    const container = document.getElementById(containerId);
    if (!input || !container) return;

    input.addEventListener('input', () => {
        const password = input.value;
        if (!password) {
            container.style.display = 'none';
            return;
        }

        container.style.display = 'block';
        const result = evaluate(password);

        // Update bar segments
        const segments = container.querySelectorAll('.strength-segment');
        segments.forEach((seg, i) => {
            seg.className = 'strength-segment';
            if (i < result.level.segments) {
                seg.classList.add(result.level.cls);
            }
        });

        // Update label
        const label = container.querySelector('.strength-label');
        label.textContent = result.level.label;
        label.className = 'strength-label ' + result.level.cls;

        // Update hints
        const hints = container.querySelector('.strength-hints');
        if (result.suggestions.length > 0) {
            hints.innerHTML = result.suggestions
                .map(s => `<span class="strength-hint">${s}</span>`)
                .join('');
            hints.style.display = 'flex';
        } else {
            hints.style.display = 'none';
        }
    });
}
