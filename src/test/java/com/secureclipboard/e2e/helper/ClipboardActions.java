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
        page.fill("#create-password", password);
        page.fill("#create-password-confirm", password);
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
        page.fill("#open-code", code);
        page.fill("#open-password", password);
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
        page.fill("#url-password", password);
        page.click("#btn-url-open");

        page.waitForSelector("#clipboard-unlocked",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(30_000));
        waitForConnected(page);
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
     */
    public static void waitForTransfersComplete(Page page) {
        page.waitForFunction(
                "() => { const el = document.getElementById('transfer-section'); " +
                        "return !el || el.style.display === 'none' || el.style.display === ''; }",
                null,
                new Page.WaitForFunctionOptions().setTimeout(120_000));
    }
}
