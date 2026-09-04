package com.secureclipboard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page loads its ES modules by plain relative path, so nothing can invalidate
 * them after a deploy except revalidation. Spring Security applies a blanket
 * no-store elsewhere, and it silently wins over resource configuration when static
 * assets share its filter chain, which is why this is asserted rather than assumed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRedisConfig.class)
@ActiveProfiles("test")
class StaticResourceCachingTest {

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void staticAssetsAreStoredAndRevalidated() throws Exception {
        for (String path : new String[]{"/js/app.js", "/js/file-store.js", "/css/styles.css"}) {
            HttpResponse<String> first = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, first.statusCode(), path + " should be served");

            String cacheControl = first.headers().firstValue("cache-control").orElse("");
            assertTrue(cacheControl.contains("no-cache"),
                    path + " should be revalidated, got: " + cacheControl);
            assertFalse(cacheControl.contains("no-store"),
                    path + " must be storable, otherwise every visit re-downloads it, got: " + cacheControl);

            String lastModified = first.headers().firstValue("last-modified")
                    .orElseThrow(() -> new AssertionError(path + " needs a validator for revalidation"));

            HttpResponse<String> conditional = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .header("If-Modified-Since", lastModified).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(304, conditional.statusCode(),
                    path + " should answer 304 while unchanged");
        }
    }
}
