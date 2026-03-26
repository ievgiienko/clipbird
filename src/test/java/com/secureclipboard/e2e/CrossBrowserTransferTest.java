package com.secureclipboard.e2e;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.secureclipboard.TestRedisConfig;
import com.secureclipboard.e2e.helper.TestFileGenerator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-browser end-to-end tests for file transfer.
 *
 * Each test uses a DIFFERENT browser engine for source and target to verify
 * that the chunked encrypted transfer protocol works across browser boundaries.
 * Tests exercise multi-chunk files to ensure per-chunk hashing and OPFS/fallback
 * writing work correctly on each engine.
 *
 * Only two browsers are launched at a time (per test) to avoid CPU contention
 * during PBKDF2 key derivation (600K iterations).
 *
 * Browser matrix (source -> target):
 *   Chromium -> Firefox
 *   Firefox  -> Chromium
 *   Chromium -> WebKit
 *   Firefox  -> WebKit
 *   (WebKit as source is excluded on Windows due to Playwright limitations)
 *
 * Prerequisites:
 *   Install all Playwright browsers before first run:
 *     mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI
 *       -D exec.args="install" -D exec.classpathScope=test
 *
 * Run:
 *   mvn test -Dtest=CrossBrowserTransferTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class CrossBrowserTransferTest {

    @LocalServerPort
    int port;

    String baseUrl;
    Playwright playwright;

    @TempDir
    Path tempDir;

    static final String PASSWORD = "CrossBrowser99!";
    static final int CHUNK_SIZE = 1_048_576; // 1 MB
    static final int CRYPTO_TIMEOUT_MS = 120_000;

    @BeforeAll
    void init() {
        baseUrl = "http://localhost:" + port;
        playwright = Playwright.create();
    }

    @AfterAll
    void teardown() {
        if (playwright != null) playwright.close();
    }

    // ================================================================
    // Browser launcher
    // ================================================================

    private Browser launch(String name) {
        var opts = new BrowserType.LaunchOptions().setHeadless(true);
        return switch (name) {
            case "chromium" -> playwright.chromium().launch(opts);
            case "firefox" -> playwright.firefox().launch(opts);
            case "webkit" -> playwright.webkit().launch(opts);
            default -> throw new IllegalArgumentException("Unknown browser: " + name);
        };
    }

    // ================================================================
    // Cross-browser pairs
    // ================================================================

    static Stream<Arguments> crossBrowserPairs() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        var pairs = new ArrayList<Arguments>();
        pairs.add(Arguments.of("chromium", "firefox"));
        pairs.add(Arguments.of("firefox", "chromium"));
        pairs.add(Arguments.of("chromium", "webkit"));
        pairs.add(Arguments.of("firefox", "webkit"));

        if (!isWindows) {
            pairs.add(Arguments.of("webkit", "chromium"));
            pairs.add(Arguments.of("webkit", "firefox"));
        }

        return pairs.stream();
    }

    // ================================================================
    // Clipboard helpers (longer timeouts for cross-browser contention)
    // ================================================================

    private String createClipboard(Page page) {
        page.navigate(baseUrl + "/create");
        page.fill("#create-password", PASSWORD);
        page.fill("#create-password-confirm", PASSWORD);
        page.click("#btn-create");
        page.waitForSelector("#clipboard-unlocked",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(CRYPTO_TIMEOUT_MS));
        page.waitForSelector("#connection-status.connected",
                new Page.WaitForSelectorOptions().setTimeout(CRYPTO_TIMEOUT_MS));
        return page.textContent("#clipboard-id").trim();
    }

    private void openClipboard(Page page, String code) {
        page.navigate(baseUrl + "/open");
        page.fill("#open-code", code);
        page.fill("#open-password", PASSWORD);
        page.click("#btn-open");
        page.waitForSelector("#clipboard-unlocked",
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(CRYPTO_TIMEOUT_MS));
        page.waitForSelector("#connection-status.connected",
                new Page.WaitForSelectorOptions().setTimeout(CRYPTO_TIMEOUT_MS));
    }

    // ================================================================
    // Core helper — launches 2 browsers, transfers, closes them
    // ================================================================

    private void transferFile(String srcName, String tgtName,
                              String fileName, int sizeBytes) throws Exception {
        Path testFile = TestFileGenerator.generate(tempDir, fileName, sizeBytes);
        String expectedHash = TestFileGenerator.sha256(testFile);

        Browser srcBrowser = launch(srcName);
        Browser tgtBrowser = srcName.equals(tgtName) ? srcBrowser : launch(tgtName);
        try {
            BrowserContext srcCtx = srcBrowser.newContext();
            BrowserContext tgtCtx = tgtBrowser.newContext();
            try {
                Page src = srcCtx.newPage();
                Page tgt = tgtCtx.newPage();

                src.onConsoleMessage(msg -> {
                    if ("error".equals(msg.type()))
                        System.err.println("[SRC:" + srcName + "] " + msg.text());
                });
                tgt.onConsoleMessage(msg -> {
                    if ("error".equals(msg.type()))
                        System.err.println("[TGT:" + tgtName + "] " + msg.text());
                });

                String code = createClipboard(src);
                openClipboard(tgt, code);

                src.setInputFiles("#file-input", testFile);
                tgt.waitForSelector("button[data-action='download']",
                        new Page.WaitForSelectorOptions().setTimeout(60_000));

                long timeoutMs = Math.max(60_000, sizeBytes / 1024L * 100);
                Download download = tgt.waitForDownload(
                        new Page.WaitForDownloadOptions().setTimeout(timeoutMs),
                        () -> tgt.click("button[data-action='download']"));

                Path downloadedFile = download.path();
                assertNotNull(downloadedFile, "Downloaded file path should not be null");
                String actualHash = TestFileGenerator.sha256(downloadedFile);
                int chunks = (int) Math.ceil((double) sizeBytes / CHUNK_SIZE);
                assertEquals(expectedHash, actualHash,
                        String.format("SHA-256 mismatch: %s->%s %s (%d bytes, %d chunk%s)",
                                srcName, tgtName, fileName, sizeBytes,
                                chunks, chunks != 1 ? "s" : ""));
            } finally {
                srcCtx.close();
                tgtCtx.close();
            }
        } finally {
            srcBrowser.close();
            if (tgtBrowser != srcBrowser) tgtBrowser.close();
        }
    }

    // ================================================================
    // 3 MB (3 chunks) — all cross-browser pairs
    // ================================================================

    @ParameterizedTest(name = "{0} -> {1}: 3 MB (3 chunks)")
    @MethodSource("crossBrowserPairs")
    void crossBrowser_3MB_multiChunk(String srcName, String tgtName) throws Exception {
        transferFile(srcName, tgtName,
                "xb_3mb_" + srcName + "_" + tgtName + ".bin",
                3 * CHUNK_SIZE);
    }

    // ================================================================
    // 10 MB (10 chunks) — all cross-browser pairs
    // ================================================================

    @ParameterizedTest(name = "{0} -> {1}: 10 MB (10 chunks)")
    @MethodSource("crossBrowserPairs")
    void crossBrowser_10MB_multiChunk(String srcName, String tgtName) throws Exception {
        transferFile(srcName, tgtName,
                "xb_10mb_" + srcName + "_" + tgtName + ".bin",
                10 * CHUNK_SIZE);
    }
}
