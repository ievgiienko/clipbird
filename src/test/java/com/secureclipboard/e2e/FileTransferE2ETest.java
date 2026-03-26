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

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Browser-based end-to-end tests for file transfer correctness.
 *
 * Each test creates two isolated Playwright browser contexts (source + target),
 * creates a clipboard, adds files from the source, downloads from the target,
 * and verifies SHA-256 integrity of the transferred files.
 *
 * Runs against an embedded Spring Boot server with H2 and mocked Redis —
 * no Docker or external services required.
 *
 * Prerequisites:
 *   Install Playwright browsers once before first run:
 *     mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
 *
 * Run:
 *   mvn test -Dtest=FileTransferE2ETest
 *   mvn test -Dtest=FileTransferE2ETest#transfer_1byte    (single test)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FileTransferE2ETest {

    @LocalServerPort
    int port;

    Playwright playwright;
    Browser browser;
    String baseUrl;

    @TempDir
    Path tempDir;

    static final String PASSWORD = "TestPassword123!";
    static final int CHUNK_SIZE = 1_048_576; // 1 MB — matches app default

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

    // ================================================================
    // Core helper: single-file transfer and verification
    // ================================================================

    /**
     * Full transfer cycle for a single file: create clipboard, add file from
     * source browser, download from target browser, verify SHA-256 match.
     */
    private void transferSingleFile(String fileName, int sizeBytes) throws Exception {
        Path testFile = TestFileGenerator.generate(tempDir, fileName, sizeBytes);
        String expectedHash = TestFileGenerator.sha256(testFile);

        BrowserContext srcCtx = browser.newContext();
        BrowserContext tgtCtx = browser.newContext();
        try {
            Page src = srcCtx.newPage();
            Page tgt = tgtCtx.newPage();

            // Attach console listeners for debugging failures
            src.onConsoleMessage(msg -> {
                if ("error".equals(msg.type()))
                    System.err.println("[SRC] " + msg.text());
            });
            tgt.onConsoleMessage(msg -> {
                if ("error".equals(msg.type()))
                    System.err.println("[TGT] " + msg.text());
            });

            // Source: create clipboard
            String code = ClipboardActions.createClipboard(src, baseUrl, PASSWORD);

            // Target: open same clipboard
            ClipboardActions.openClipboard(tgt, baseUrl, code, PASSWORD);

            // Source: add file via file input
            src.setInputFiles("#file-input", testFile);

            // Target: wait for file with download button.
            // This implicitly requires: connection established, source device known online,
            // file_added notification received, and file rendered with download button.
            tgt.waitForSelector("button[data-action='download']",
                    new Page.WaitForSelectorOptions().setTimeout(60_000));

            // Target: initiate download and wait for it to complete
            long timeoutMs = Math.max(60_000, sizeBytes / 1024L * 100);
            Download download = tgt.waitForDownload(
                    new Page.WaitForDownloadOptions().setTimeout(timeoutMs),
                    () -> tgt.click("button[data-action='download']"));

            // Verify file integrity
            Path downloadedFile = download.path();
            assertNotNull(downloadedFile, "Downloaded file path should not be null");
            String actualHash = TestFileGenerator.sha256(downloadedFile);
            int expectedChunks = (int) Math.ceil((double) sizeBytes / CHUNK_SIZE);
            assertEquals(expectedHash, actualHash,
                    String.format("SHA-256 mismatch for %s (%d bytes, %d chunk%s)",
                            fileName, sizeBytes, expectedChunks,
                            expectedChunks != 1 ? "s" : ""));
        } finally {
            srcCtx.close();
            tgtCtx.close();
        }
    }

    // ================================================================
    // Single-file transfer tests — various sizes
    // ================================================================

    @Test
    @Order(1)
    @DisplayName("1 byte file")
    void transfer_1byte() throws Exception {
        transferSingleFile("test_1b.bin", 1);
    }

    @Test
    @Order(2)
    @DisplayName("10 byte file")
    void transfer_10bytes() throws Exception {
        transferSingleFile("test_10b.bin", 10);
    }

    @Test
    @Order(3)
    @DisplayName("1 KB file")
    void transfer_1KB() throws Exception {
        transferSingleFile("test_1kb.bin", 1024);
    }

    @Test
    @Order(4)
    @DisplayName("100 KB file")
    void transfer_100KB() throws Exception {
        transferSingleFile("test_100kb.bin", 100 * 1024);
    }

    @Test
    @Order(5)
    @DisplayName("256 KB file (minimum chunk size boundary)")
    void transfer_256KB() throws Exception {
        transferSingleFile("test_256kb.bin", 256 * 1024);
    }

    @Test
    @Order(6)
    @DisplayName("Just below chunk size (1 MB - 1 byte)")
    void transfer_belowChunkSize() throws Exception {
        transferSingleFile("test_below_chunk.bin", CHUNK_SIZE - 1);
    }

    @Test
    @Order(7)
    @DisplayName("Exact chunk size (1 MB)")
    void transfer_exactChunkSize() throws Exception {
        transferSingleFile("test_exact_chunk.bin", CHUNK_SIZE);
    }

    @Test
    @Order(8)
    @DisplayName("Just above chunk size (1 MB + 1 byte, forces 2 chunks)")
    void transfer_aboveChunkSize() throws Exception {
        transferSingleFile("test_above_chunk.bin", CHUNK_SIZE + 1);
    }

    @Test
    @Order(9)
    @DisplayName("3 MB file (3 chunks)")
    void transfer_3MB() throws Exception {
        transferSingleFile("test_3mb.bin", 3 * CHUNK_SIZE);
    }

    @Test
    @Order(10)
    @DisplayName("10 MB file (10 chunks)")
    void transfer_10MB() throws Exception {
        transferSingleFile("test_10mb.bin", 10 * CHUNK_SIZE);
    }

    @Test
    @Order(11)
    @DisplayName("50 MB file (50 chunks)")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void transfer_50MB() throws Exception {
        transferSingleFile("test_50mb.bin", 50 * CHUNK_SIZE);
    }

    @Test
    @Order(12)
    @DisplayName("150 MB file (150 chunks)")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void transfer_150MB() throws Exception {
        transferSingleFile("test_150mb.bin", 150 * CHUNK_SIZE);
    }

    // ================================================================
    // Multi-file tests
    // ================================================================

    @Test
    @Order(20)
    @DisplayName("Multiple files downloaded sequentially")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void transfer_multipleFilesSequential() throws Exception {
        Path file1 = TestFileGenerator.generate(tempDir, "seq_1.bin", 512);
        Path file2 = TestFileGenerator.generate(tempDir, "seq_2.bin", 50 * 1024);
        Path file3 = TestFileGenerator.generate(tempDir, "seq_3.bin", CHUNK_SIZE + 100);

        Set<String> expectedHashes = Set.of(
                TestFileGenerator.sha256(file1),
                TestFileGenerator.sha256(file2),
                TestFileGenerator.sha256(file3));

        BrowserContext srcCtx = browser.newContext();
        BrowserContext tgtCtx = browser.newContext();
        try {
            Page src = srcCtx.newPage();
            Page tgt = tgtCtx.newPage();

            String code = ClipboardActions.createClipboard(src, baseUrl, PASSWORD);
            ClipboardActions.openClipboard(tgt, baseUrl, code, PASSWORD);

            // Add all files at once
            src.setInputFiles("#file-input", new Path[]{file1, file2, file3});

            // Wait for all files and download buttons on target
            ClipboardActions.waitForDownloadButtons(tgt, 3);

            // Download files one at a time
            Set<String> actualHashes = new HashSet<>();
            List<Locator> downloadBtns = tgt.locator("button[data-action='download']").all();
            assertEquals(3, downloadBtns.size(), "Should have 3 download buttons");

            for (int i = 0; i < downloadBtns.size(); i++) {
                final int idx = i;
                Download download = tgt.waitForDownload(
                        new Page.WaitForDownloadOptions().setTimeout(120_000),
                        () -> downloadBtns.get(idx).click());
                actualHashes.add(TestFileGenerator.sha256(download.path()));

                // Wait for current transfer to finish before starting next
                if (i < downloadBtns.size() - 1) {
                    ClipboardActions.waitForTransfersComplete(tgt);
                }
            }

            assertEquals(expectedHashes, actualHashes,
                    "All three files should match their originals");
        } finally {
            srcCtx.close();
            tgtCtx.close();
        }
    }

    @Test
    @Order(21)
    @DisplayName("Download All button")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void transfer_downloadAll() throws Exception {
        Path file1 = TestFileGenerator.generate(tempDir, "batch_1.bin", 2048);
        Path file2 = TestFileGenerator.generate(tempDir, "batch_2.bin", 100 * 1024);

        Set<String> expectedHashes = Set.of(
                TestFileGenerator.sha256(file1),
                TestFileGenerator.sha256(file2));

        BrowserContext srcCtx = browser.newContext();
        BrowserContext tgtCtx = browser.newContext();
        try {
            Page src = srcCtx.newPage();
            Page tgt = tgtCtx.newPage();

            String code = ClipboardActions.createClipboard(src, baseUrl, PASSWORD);
            ClipboardActions.openClipboard(tgt, baseUrl, code, PASSWORD);

            // Add files
            src.setInputFiles("#file-input", new Path[]{file1, file2});
            ClipboardActions.waitForFileCount(tgt, 2);

            // Wait for Download All button (appears when 2+ downloadable files)
            ClipboardActions.waitForDownloadButtons(tgt, 2);
            tgt.waitForSelector("#btn-download-all",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

            // Collect downloads via listener (must use Playwright waits to keep event loop alive)
            List<Download> downloads = new CopyOnWriteArrayList<>();
            tgt.onDownload(downloads::add);

            tgt.click("#btn-download-all");

            // Wait for batch to start (cancel button appears)
            tgt.waitForSelector("#btn-cancel-download-all",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));

            // Wait for batch to complete (Download All button returns)
            tgt.waitForSelector("#btn-download-all",
                    new Page.WaitForSelectorOptions().setTimeout(120_000));

            // Small delay for final download event to register
            tgt.waitForTimeout(2000);

            assertEquals(2, downloads.size(),
                    "Should have captured 2 downloads");

            // Verify all downloaded files
            Set<String> actualHashes = new HashSet<>();
            for (Download d : downloads) {
                actualHashes.add(TestFileGenerator.sha256(d.path()));
            }
            assertEquals(expectedHashes, actualHashes,
                    "All batch-downloaded files should match originals");
        } finally {
            srcCtx.close();
            tgtCtx.close();
        }
    }

    // ================================================================
    // Access pattern tests
    // ================================================================

    @Test
    @Order(30)
    @DisplayName("Transfer via direct URL access (locked state)")
    void transfer_viaDirectUrl() throws Exception {
        Path testFile = TestFileGenerator.generate(tempDir, "url_test.bin", 5000);
        String expectedHash = TestFileGenerator.sha256(testFile);

        BrowserContext srcCtx = browser.newContext();
        BrowserContext tgtCtx = browser.newContext();
        try {
            Page src = srcCtx.newPage();
            Page tgt = tgtCtx.newPage();

            // Source: create clipboard
            String code = ClipboardActions.createClipboard(src, baseUrl, PASSWORD);

            // Target: open via direct URL (goes through locked state)
            ClipboardActions.openClipboardViaUrl(tgt, baseUrl, code, PASSWORD);

            // Source: add file
            src.setInputFiles("#file-input", testFile);

            // Target: wait for download button
            tgt.waitForSelector("button[data-action='download']",
                    new Page.WaitForSelectorOptions().setTimeout(60_000));
            Download download = tgt.waitForDownload(
                    new Page.WaitForDownloadOptions().setTimeout(60_000),
                    () -> tgt.click("button[data-action='download']"));

            assertEquals(expectedHash, TestFileGenerator.sha256(download.path()),
                    "File transferred via direct URL should match original");
        } finally {
            srcCtx.close();
            tgtCtx.close();
        }
    }

    @Test
    @Order(31)
    @DisplayName("Mixed file sizes in a single clipboard via Download All")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void transfer_mixedSizes() throws Exception {
        int[] sizes = {1, 100, 1024, 10 * 1024, 256 * 1024, CHUNK_SIZE, CHUNK_SIZE * 2 + 500};
        Set<String> expectedHashes = new HashSet<>();
        Path[] files = new Path[sizes.length];

        for (int i = 0; i < sizes.length; i++) {
            String name = "mixed_" + i + "_" + sizes[i] + ".bin";
            files[i] = TestFileGenerator.generate(tempDir, name, sizes[i]);
            expectedHashes.add(TestFileGenerator.sha256(files[i]));
        }

        BrowserContext srcCtx = browser.newContext();
        BrowserContext tgtCtx = browser.newContext();
        try {
            Page src = srcCtx.newPage();
            Page tgt = tgtCtx.newPage();

            String code = ClipboardActions.createClipboard(src, baseUrl, PASSWORD);
            ClipboardActions.openClipboard(tgt, baseUrl, code, PASSWORD);

            // Add all files
            src.setInputFiles("#file-input", files);
            ClipboardActions.waitForFileCount(tgt, sizes.length);

            // Download All
            ClipboardActions.waitForDownloadButtons(tgt, sizes.length);
            tgt.waitForSelector("#btn-download-all",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

            List<Download> downloads = new CopyOnWriteArrayList<>();
            tgt.onDownload(downloads::add);

            tgt.click("#btn-download-all");

            // Wait for batch to start then complete
            tgt.waitForSelector("#btn-cancel-download-all",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
            tgt.waitForSelector("#btn-download-all",
                    new Page.WaitForSelectorOptions().setTimeout(300_000));
            tgt.waitForTimeout(2000);

            assertEquals(sizes.length, downloads.size(),
                    "Should have captured " + sizes.length + " downloads");

            Set<String> actualHashes = new HashSet<>();
            for (Download d : downloads) {
                actualHashes.add(TestFileGenerator.sha256(d.path()));
            }
            assertEquals(expectedHashes, actualHashes,
                    "All mixed-size files should match originals");
        } finally {
            srcCtx.close();
            tgtCtx.close();
        }
    }

    // ================================================================
    // Resilience tests
    // ================================================================

    @Test
    @Order(35)
    @DisplayName("Big file download succeeds while both devices delete other files in parallel")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void transfer_deleteFilesDuringDownload() throws Exception {
        // Generate files: both devices will add multiple files, then start a big download
        // while simultaneously deleting the other files
        int bigFileSize = 10 * CHUNK_SIZE; // 10 MB — enough chunks to overlap with deletions
        Path srcFile1 = TestFileGenerator.generate(tempDir, "src_big.bin", bigFileSize);
        Path srcFile2 = TestFileGenerator.generate(tempDir, "src_small_1.bin", 1024);
        Path srcFile3 = TestFileGenerator.generate(tempDir, "src_small_2.bin", 50 * 1024);
        Path tgtFile1 = TestFileGenerator.generate(tempDir, "tgt_small_1.bin", 2048);
        Path tgtFile2 = TestFileGenerator.generate(tempDir, "tgt_small_2.bin", 30 * 1024);

        String expectedBigHash = TestFileGenerator.sha256(srcFile1);

        BrowserContext srcCtx = browser.newContext();
        BrowserContext tgtCtx = browser.newContext();
        try {
            Page src = srcCtx.newPage();
            Page tgt = tgtCtx.newPage();

            src.onConsoleMessage(msg -> {
                if ("error".equals(msg.type()))
                    System.err.println("[SRC] " + msg.text());
            });
            tgt.onConsoleMessage(msg -> {
                if ("error".equals(msg.type()))
                    System.err.println("[TGT] " + msg.text());
            });

            // Source: create clipboard
            String code = ClipboardActions.createClipboard(src, baseUrl, PASSWORD);

            // Target: join
            ClipboardActions.openClipboard(tgt, baseUrl, code, PASSWORD);

            // Source adds its files (big + 2 small)
            src.setInputFiles("#file-input", new Path[]{srcFile1, srcFile2, srcFile3});

            // Target adds its files (2 small)
            tgt.setInputFiles("#file-input", new Path[]{tgtFile1, tgtFile2});

            // Wait for both sides to see all 5 files
            ClipboardActions.waitForFileCount(src, 5);
            ClipboardActions.waitForFileCount(tgt, 5);

            // Target: wait for download buttons for source's files (3 files from source)
            ClipboardActions.waitForDownloadButtons(tgt, 3);

            // Identify the big file's download button on target side
            // The big file has the most chunks, so find the file item showing "10 chunk"
            tgt.waitForFunction(
                    "() => { const items = document.querySelectorAll('.file-item'); " +
                    "for (const item of items) { " +
                    "  const meta = item.querySelector('.file-meta'); " +
                    "  if (meta && meta.textContent.includes('10 chunk')) return true; " +
                    "} return false; }",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(15_000));

            // Start downloading the big file
            Download download = tgt.waitForDownload(
                    new Page.WaitForDownloadOptions().setTimeout(300_000),
                    () -> {
                        // Click the download button on the file item that has "10 chunk" in its meta
                        tgt.evaluate(
                            "() => { const items = document.querySelectorAll('.file-item'); " +
                            "for (const item of items) { " +
                            "  const meta = item.querySelector('.file-meta'); " +
                            "  if (meta && meta.textContent.includes('10 chunk')) { " +
                            "    item.querySelector('button[data-action=\"download\"]').click(); " +
                            "    return; " +
                            "  } } }");

                        // Wait for transfer to start (transfer section visible with at least 1 chunk)
                        tgt.waitForFunction(
                                "() => { const el = document.getElementById('transfer-section'); " +
                                "return el && el.style.display === 'block'; }",
                                null,
                                new Page.WaitForFunctionOptions().setTimeout(30_000));

                        // Now both devices delete their other files while transfer is ongoing
                        // Source removes its 2 small files
                        src.locator("button[data-action='remove']").first().click();
                        src.waitForTimeout(500);
                        // After first removal, there may still be another remove button
                        if (src.locator("button[data-action='remove']").count() > 0) {
                            src.locator("button[data-action='remove']").first().click();
                        }

                        // Target removes its 2 files
                        tgt.locator("button[data-action='remove']").first().click();
                        tgt.waitForTimeout(500);
                        if (tgt.locator("button[data-action='remove']").count() > 0) {
                            tgt.locator("button[data-action='remove']").first().click();
                        }
                    });

            // Verify the big file transferred correctly despite parallel deletions
            Path downloadedFile = download.path();
            assertNotNull(downloadedFile, "Downloaded file path should not be null");
            String actualHash = TestFileGenerator.sha256(downloadedFile);
            assertEquals(expectedBigHash, actualHash,
                    "Big file SHA-256 should match despite other files being deleted during transfer");

            // Verify that the deleted files are actually gone from the UI
            // Only the big file (from source, non-own on target) should remain — no download button
            // since transfer is complete, and source should have 1 file left (the big one, shown as own)
            src.waitForFunction(
                    "() => document.querySelectorAll('.file-item').length <= 1",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(15_000));
        } finally {
            srcCtx.close();
            tgtCtx.close();
        }
    }

    @Test
    @Order(40)
    @DisplayName("File unavailable when source goes offline")
    void transfer_sourceGoesOffline() throws Exception {
        Path testFile = TestFileGenerator.generate(tempDir, "offline_test.bin", 1024);

        BrowserContext srcCtx = browser.newContext();
        BrowserContext tgtCtx = browser.newContext();
        try {
            Page src = srcCtx.newPage();
            Page tgt = tgtCtx.newPage();

            String code = ClipboardActions.createClipboard(src, baseUrl, PASSWORD);
            ClipboardActions.openClipboard(tgt, baseUrl, code, PASSWORD);

            // Source: add file
            src.setInputFiles("#file-input", testFile);

            // Verify download button is available while source is online
            tgt.waitForSelector("button[data-action='download']",
                    new Page.WaitForSelectorOptions().setTimeout(60_000));

            // Source goes offline (close the source context)
            srcCtx.close();

            // Wait for target to detect source offline
            tgt.waitForFunction(
                    "() => document.querySelector('.file-source-status.offline') !== null",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(30_000));

            // Download button should be gone or disabled
            tgt.waitForFunction(
                    "() => document.querySelectorAll('button[data-action=\"download\"]').length === 0",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(10_000));

        } finally {
            // srcCtx may already be closed
            try { srcCtx.close(); } catch (Exception ignored) {}
            tgtCtx.close();
        }
    }

    @Test
    @Order(41)
    @DisplayName("Target can download after reconnecting")
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void transfer_targetReconnects() throws Exception {
        Path testFile = TestFileGenerator.generate(tempDir, "reconnect_test.bin", 5000);
        String expectedHash = TestFileGenerator.sha256(testFile);

        BrowserContext srcCtx = browser.newContext();
        BrowserContext tgtCtx = browser.newContext();
        try {
            Page src = srcCtx.newPage();
            Page tgt = tgtCtx.newPage();

            String code = ClipboardActions.createClipboard(src, baseUrl, PASSWORD);
            ClipboardActions.openClipboard(tgt, baseUrl, code, PASSWORD);

            // Source: add file
            src.setInputFiles("#file-input", testFile);
            tgt.waitForSelector("button[data-action='download']",
                    new Page.WaitForSelectorOptions().setTimeout(60_000));

            // Target disconnects and reconnects by navigating away and back
            tgt.navigate(baseUrl + "/");
            tgt.waitForSelector("#landing-page",
                    new Page.WaitForSelectorOptions().setTimeout(5_000));

            // Reopen clipboard — file should still be available after reconnect
            ClipboardActions.openClipboard(tgt, baseUrl, code, PASSWORD);
            tgt.waitForSelector("button[data-action='download']",
                    new Page.WaitForSelectorOptions().setTimeout(60_000));

            // Download and verify
            Download download = tgt.waitForDownload(
                    new Page.WaitForDownloadOptions().setTimeout(60_000),
                    () -> tgt.click("button[data-action='download']"));

            assertEquals(expectedHash, TestFileGenerator.sha256(download.path()),
                    "File should transfer correctly after target reconnects");
        } finally {
            srcCtx.close();
            tgtCtx.close();
        }
    }
}
