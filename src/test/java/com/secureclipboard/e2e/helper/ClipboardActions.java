package com.secureclipboard.e2e.helper;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Playwright page interaction helpers for clipboard creation and navigation.
 */
public final class ClipboardActions {

    private ClipboardActions() {}

    /**
     * Create a new clipboard via the UI. Returns the clipboard code.
     */
    public static String createClipboard(Page page, String baseUrl, String password) {
        page.navigate(baseUrl + "/create");
        fillForm(page, "#create-password", password, "#create-password-confirm", password);
        page.click("#btn-create");

        page.waitForSelector("#clipboard-unlocked",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(30_000));
        waitForConnected(page);

        return page.textContent("#clipboard-id").trim();
    }

    /**
     * Open an existing clipboard via the Open page UI.
     */
    public static void openClipboard(Page page, String baseUrl, String code, String password) {
        page.navigate(baseUrl + "/open");
        fillForm(page, "#open-code", code, "#open-password", password);
        page.click("#btn-open");

        page.waitForSelector("#clipboard-unlocked",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(30_000));
        waitForConnected(page);
    }

    /**
     * Open a clipboard via direct URL (shows locked state first, then enter password).
     */
    public static void openClipboardViaUrl(Page page, String baseUrl, String code, String password) {
        page.navigate(baseUrl + "/c/" + code);
        page.waitForSelector("#clipboard-locked",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(10_000));
        fillForm(page, "#url-password", password);
        page.click("#btn-url-open");

        page.waitForSelector("#clipboard-unlocked",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(30_000));
        waitForConnected(page);
    }

    /**
     * Fill a form and make sure the values survived.
     *
     * Route handling clears these forms and puts the caret in the first field, both
     * asynchronously. Anything typed before that lands is either wiped or redirected
     * into whichever field the page focused, and the page then reports an empty field
     * instead of proceeding. Checking what the inputs actually hold and retyping is
     * stable whenever the page settles, and unlike waiting for the caret it does not
     * assume the page takes focus at all, which it deliberately no longer does when
     * something else is already focused.
     *
     * @param selectorsAndValues selector, value, selector, value, ...
     */
    public static void fillForm(Page page, String... selectorsAndValues) {
        for (int attempt = 0; attempt < 3; attempt++) {
            for (int i = 0; i < selectorsAndValues.length; i += 2) {
                page.fill(selectorsAndValues[i], selectorsAndValues[i + 1]);
            }
            if (valuesHeld(page, selectorsAndValues)) {
                return;
            }
        }
        throw new AssertionError("Form kept resetting itself: " + describe(page, selectorsAndValues));
    }

    private static boolean valuesHeld(Page page, String... selectorsAndValues) {
        for (int i = 0; i < selectorsAndValues.length; i += 2) {
            if (!selectorsAndValues[i + 1].equals(page.inputValue(selectorsAndValues[i]))) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Page page, String... selectorsAndValues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selectorsAndValues.length; i += 2) {
            sb.append(selectorsAndValues[i]).append("=\"").append(page.inputValue(selectorsAndValues[i])).append("\" ");
        }
        return sb.toString().trim();
    }

    /**
     * Wait for the WebSocket connection status to show "Connected".
     */
    public static void waitForConnected(Page page) {
        page.waitForSelector("#connection-status.connected",
                new Page.WaitForSelectorOptions().setTimeout(15_000));
    }

    /**
     * Wait until the device count display includes the expected count.
     */
    public static void waitForDeviceCount(Page page, int count) {
        page.waitForFunction(
                "count => document.getElementById('device-count').textContent.includes(count + ' device')",
                count,
                new Page.WaitForFunctionOptions().setTimeout(30_000));
    }

    /**
     * Wait until at least {@code count} file items are visible on the page.
     */
    public static void waitForFileCount(Page page, int count) {
        page.waitForFunction(
                "count => document.querySelectorAll('.file-item').length >= count",
                count,
                new Page.WaitForFunctionOptions().setTimeout(30_000));
    }

    /**
     * Wait until at least {@code count} download buttons are visible.
     */
    public static void waitForDownloadButtons(Page page, int count) {
        page.waitForFunction(
                "count => document.querySelectorAll('button[data-action=\"download\"]').length >= count",
                count,
                new Page.WaitForFunctionOptions().setTimeout(30_000));
    }

    /**
     * Wait for the transfer section to become hidden (all transfers complete).
     *
     * Asks for the computed style rather than the inline one. The markup happens to
     * set display inline today, and a condition written against that reads as a guard
     * while silently accepting an empty string, which would pass before a transfer had
     * even started.
     */
    public static void waitForTransfersComplete(Page page) {
        page.waitForFunction(
                "() => { const el = document.getElementById('transfer-section'); " +
                        "return !el || getComputedStyle(el).display === 'none'; }",
                null,
                new Page.WaitForFunctionOptions().setTimeout(120_000));
    }
}
