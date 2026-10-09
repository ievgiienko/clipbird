package com.secureclipboard.e2e;

import com.microsoft.playwright.*;
import com.secureclipboard.TestRedisConfig;
import com.secureclipboard.e2e.helper.ClipboardActions;
import com.secureclipboard.e2e.helper.TestFileGenerator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Account flows end to end: registration, sign-in, sign-out, and a file round trip
 * made by a signed-in user.
 *
 * Each test registers its own account and opens the contexts it needs, so any one of
 * them can be run on its own and none can be left broken by another.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserFlowE2ETest {

    @LocalServerPort
    int port;

    private Playwright playwright;
    private Browser browser;
    private String baseUrl;

    private static final String PASSWORD = "TestPassword123!";
    private static final int FILE_SIZE = 100 * 1024;

    @TempDir
    Path tempDir;

    @BeforeAll
    void setupBrowser() {
        playwright = Playwright.create();

        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(true)
        );

        baseUrl = "http://localhost:" + port;
    }

    @AfterAll
    void teardownBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Test
    @DisplayName("Registration signs the new account in")
    void registerUser() {
        BrowserContext context = browser.newContext();
        try {
            Page page = context.newPage();
            register(page, freshEmail());

            assertTrue(page.url().contains("/dashboard"));
            assertTrue(page.locator("#btn-nav-logout").isVisible());
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("A registered account can sign in again")
    void loginUser() {
        String email = registerAccount();

        BrowserContext context = browser.newContext();
        try {
            Page page = context.newPage();
            login(page, email);

            assertTrue(page.url().contains("/dashboard"));
            assertTrue(page.locator("#btn-nav-logout").isVisible());
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("Signing out returns to the login page")
    void logoutUser() {
        String email = registerAccount();

        BrowserContext context = browser.newContext();
        try {
            Page page = context.newPage();
            login(page, email);

            page.locator("#btn-nav-logout").click();
            page.waitForURL(url -> url.contains("/login"));

            assertTrue(page.url().contains("/login"));
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("A signed-in user can add a file to a clipboard")
    void uploadFile() throws Exception {
        String email = registerAccount();
        Path file = TestFileGenerator.generate(tempDir, "upload-test.txt", FILE_SIZE);

        BrowserContext context = browser.newContext();
        try {
            Page page = context.newPage();
            login(page, email);

            ClipboardActions.createClipboard(page, baseUrl, PASSWORD);

            page.setInputFiles("#file-input", file);

            ClipboardActions.waitForFileCount(page, 1);
            ClipboardActions.waitForTransfersComplete(page);

            assertEquals(1, page.locator(".file-item").count());
        } finally {
            context.close();
        }
    }

    /**
     * A file is served by the device that added it, so the source has to stay open for
     * the whole download: the target only offers a Download button while the source is
     * connected, and the bytes travel from it rather than from the server.
     */
    @Test
    @DisplayName("A second device downloads the file the user uploaded")
    void downloadFile() throws Exception {
        String email = registerAccount();
        Path original = TestFileGenerator.generate(tempDir, "download-test.txt", FILE_SIZE);

        BrowserContext sourceContext = browser.newContext();
        BrowserContext targetContext = browser.newContext();
        try {
            Page source = sourceContext.newPage();
            login(source, email);
            String clipboardCode = ClipboardActions.createClipboard(source, baseUrl, PASSWORD);

            Page target = targetContext.newPage();
            ClipboardActions.openClipboard(target, baseUrl, clipboardCode, PASSWORD);

            source.setInputFiles("#file-input", original);
            ClipboardActions.waitForDownloadButtons(target, 1);

            Download download = target.waitForDownload(
                    new Page.WaitForDownloadOptions().setTimeout(60_000),
                    () -> target.locator("button[data-action='download']").first().click()
            );

            Path downloadedFile = tempDir.resolve("downloaded-" + original.getFileName());
            download.saveAs(downloadedFile);

            assertTrue(Files.exists(downloadedFile));
            assertTrue(Files.size(downloadedFile) > 0);

            assertEquals(
                    TestFileGenerator.sha256(original),
                    TestFileGenerator.sha256(downloadedFile)
            );
        } finally {
            sourceContext.close();
            targetContext.close();
        }
    }

    private String freshEmail() {
        return "user-" + UUID.randomUUID() + "@test.com";
    }

    /**
     * Register an account in a throwaway context, so the test's own context starts
     * signed out and exercises the sign-in it is about.
     */
    private String registerAccount() {
        String email = freshEmail();
        BrowserContext context = browser.newContext();
        try {
            register(context.newPage(), email);
        } finally {
            context.close();
        }
        return email;
    }

    private void register(Page page, String email) {
        page.navigate(baseUrl + "/register");
        ClipboardActions.fillForm(page,
                "#reg-email", email,
                "#reg-password", PASSWORD,
                "#reg-password-confirm", PASSWORD);
        page.click("#btn-register");
        page.waitForURL(url -> url.contains("/dashboard"));
    }

    private void login(Page page, String email) {
        page.navigate(baseUrl + "/login");
        ClipboardActions.fillForm(page, "#login-email", email, "#login-password", PASSWORD);
        page.click("#btn-login");
        page.waitForURL(url -> url.contains("/dashboard"));
    }
}
