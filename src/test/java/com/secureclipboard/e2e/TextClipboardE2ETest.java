package com.secureclipboard.e2e;

import com.microsoft.playwright.*;
import com.secureclipboard.TestRedisConfig;
import com.secureclipboard.e2e.helper.ClipboardActions;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextClipboardE2ETest {

    @LocalServerPort
    int port;

    Playwright playwright;
    Browser browser;
    String baseUrl;

    static final String PASSWORD = "TestPassword123!";

    @BeforeAll
    void setupBrowser() {
        baseUrl = "http://localhost:" + port;
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    void teardownBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    // Verifies that a new clipboard can be created through the UI and a valid clipboard code is displayed.
    @Test
    @Order(1)
    @DisplayName("Create clipboard")
    void createClipboard() {
        BrowserContext context = browser.newContext();
        try {
            Page page = context.newPage();

            String code = ClipboardActions.createClipboard(page, baseUrl, PASSWORD);

            assertNotNull(code);
            assertFalse(code.isBlank());

            assertTrue(page.isVisible("#clipboard-unlocked"));
            assertTrue(page.textContent("#clipboard-id").contains(code));

        } finally {
            context.close();
        }
    }

    // Verifies that an existing clipboard can be opened through the UI using a valid code and password.
    @Test
    @Order(2)
    @DisplayName("Open clipboard")
    void openClipboard() {
        BrowserContext sourceContext = browser.newContext();
        BrowserContext targetContext = browser.newContext();

        try {
            Page source = sourceContext.newPage();
            Page target = targetContext.newPage();

            String code = ClipboardActions.createClipboard(source, baseUrl, PASSWORD);

            target.navigate(baseUrl + "/open");
            target.fill("#open-code", code);
            target.fill("#open-password", PASSWORD);
            target.click("#btn-open");

            target.waitForTimeout(1000);

            System.out.println("Current URL: " + target.url());
            System.out.println("Body:");
            System.out.println(target.locator("body").innerText());

            target.waitForSelector("#clipboard-unlocked");

            assertTrue(target.isVisible("#clipboard-unlocked"));
            assertEquals(code, target.textContent("#clipboard-id").trim());

        } finally {
            sourceContext.close();
            targetContext.close();
        }
    }

    // Verifies that clipboard text can be updated through the UI and the entered value is displayed.
    @Test
    @Order(3)
    @DisplayName("Update clipboard text")
    void updateClipboardText() {
        BrowserContext context = browser.newContext();

        try {
            Page page = context.newPage();

            ClipboardActions.createClipboard(page, baseUrl, PASSWORD);

            String text = "Updated clipboard text";

            Locator textArea = page.locator("#clipboard-text");
            textArea.click();
            textArea.pressSequentially(text);

            // Wait longer than the frontend debounce (500 ms)
            page.waitForTimeout(1500);

            assertEquals(text, textArea.inputValue());

        } finally {
            context.close();
        }
    }

    // Verifies that text entered on one connected device is synchronized to another device in real time.
    @Test
    @Order(4)
    @DisplayName("Synchronize text between devices")
    void synchronizeTextBetweenDevices() {
        BrowserContext sourceContext = browser.newContext();
        BrowserContext targetContext = browser.newContext();

        try {
            Page source = sourceContext.newPage();
            Page target = targetContext.newPage();

            String code = ClipboardActions.createClipboard(source, baseUrl, PASSWORD);
            ClipboardActions.openClipboard(target, baseUrl, code, PASSWORD);

            String text = "Hello from Playwright!";

            Locator sourceText = source.locator("#clipboard-text");
            sourceText.click();
            sourceText.pressSequentially(text);

            // Wait longer than the frontend debounce (500 ms)
            source.waitForTimeout(1500);

            target.waitForFunction(
                    "expected => document.getElementById('clipboard-text').value === expected",
                    text,
                    new Page.WaitForFunctionOptions().setTimeout(30_000)
            );

            assertEquals(
                    text,
                    target.locator("#clipboard-text").inputValue()
            );

        } finally {
            sourceContext.close();
            targetContext.close();
        }
    }

    // Verifies that a clipboard can be permanently deleted through the UI after user confirmation.
    @Test
    @Order(5)
    @DisplayName("Delete clipboard")
    void deleteClipboard() {
        BrowserContext context = browser.newContext();
        try {
            Page page = context.newPage();

            ClipboardActions.createClipboard(page, baseUrl, PASSWORD);

            page.click("#btn-delete");

            page.waitForSelector("#confirm-delete-modal");

            page.click("#btn-confirm-delete");

            page.waitForSelector("#deleted-overlay");

            assertTrue(page.isVisible("#deleted-overlay"));
            assertTrue(page.textContent("#deleted-overlay")
                    .contains("Clipboard Deleted"));

        } finally {
            context.close();
        }
    }
}