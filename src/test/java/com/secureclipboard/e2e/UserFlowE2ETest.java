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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserFlowE2ETest {

    @LocalServerPort
    int port;

    private Playwright playwright;
    private Browser browser;
    private String baseUrl;

    private static final String PASSWORD = "TestPassword123!";

    private String email;
    private String clipboardCode;
    private Path uploadedFile;

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
    @Order(1)
    void registerUser() {

        BrowserContext context = browser.newContext();
        Page page = context.newPage();

        email = "user-" + UUID.randomUUID() + "@test.com";

        page.navigate(baseUrl + "/register");

        page.locator("#reg-email").fill(email);
        page.locator("#reg-password").fill(PASSWORD);
        page.locator("#reg-password-confirm").fill(PASSWORD);

        page.locator("#btn-register").click();

        page.waitForURL(url -> url.contains("/dashboard"));

        assertTrue(page.url().contains("/dashboard"));
        assertTrue(page.locator("#btn-nav-logout").isVisible());

        context.close();
    }

    @Test
    @Order(2)
    void loginUser() {

        BrowserContext context = browser.newContext();
        Page page = context.newPage();

        page.navigate(baseUrl + "/login");

        page.locator("#login-email").fill(email);
        page.locator("#login-password").fill(PASSWORD);

        page.locator("#btn-login").click();

        page.waitForURL(url -> url.contains("/dashboard"));

        assertTrue(page.url().contains("/dashboard"));
        assertTrue(page.locator("#btn-nav-logout").isVisible());

        context.close();
    }

    @Test
    @Order(3)
    void logoutUser() {

        BrowserContext context = browser.newContext();
        Page page = context.newPage();

        page.navigate(baseUrl + "/login");

        page.locator("#login-email").fill(email);
        page.locator("#login-password").fill(PASSWORD);

        page.locator("#btn-login").click();

        page.waitForURL(url -> url.contains("/dashboard"));

        page.locator("#btn-nav-logout").click();

        page.waitForURL(url -> url.contains("/login"));

        assertTrue(page.url().contains("/login"));

        context.close();
    }

    @Test
    @Order(4)
    void uploadFile() throws Exception {

        BrowserContext context = browser.newContext();
        Page page = context.newPage();

        page.navigate(baseUrl + "/login");

        page.locator("#login-email").fill(email);
        page.locator("#login-password").fill(PASSWORD);

        page.locator("#btn-login").click();

        page.waitForURL(url -> url.contains("/dashboard"));

        clipboardCode = ClipboardActions.createClipboard(
                page,
                baseUrl,
                PASSWORD
        );

        uploadedFile = TestFileGenerator.generate(
                tempDir,
                "upload-test.txt",
                1024 * 100
        );

        page.setInputFiles("#file-input", uploadedFile);

        ClipboardActions.waitForFileCount(page, 1);
        ClipboardActions.waitForTransfersComplete(page);

        assertEquals(1, page.locator(".file-item").count());

        context.close();
    }

    @Test
    @Order(5)
    void downloadFile() throws Exception {

        BrowserContext context = browser.newContext();
        Page page = context.newPage();

        ClipboardActions.openClipboard(
                page,
                baseUrl,
                clipboardCode,
                PASSWORD
        );

        ClipboardActions.waitForDownloadButtons(page, 1);

        Download download = page.waitForDownload(() ->
                page.locator("button[data-action='download']").first().click()
        );

        Path downloadedFile = tempDir.resolve("downloaded-upload-test.txt");
        download.saveAs(downloadedFile);

        assertTrue(Files.exists(downloadedFile));
        assertTrue(Files.size(downloadedFile) > 0);

        assertEquals(
                TestFileGenerator.sha256(uploadedFile),
                TestFileGenerator.sha256(downloadedFile)
        );

        context.close();
    }
}