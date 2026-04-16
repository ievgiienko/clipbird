package com.secureclipboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private ClipboardProps clipboard = new ClipboardProps();
    private CleanupProps cleanup = new CleanupProps();
    private RateLimitProps rateLimit = new RateLimitProps();
    private TelegramProps telegram = new TelegramProps();

    public ClipboardProps getClipboard() { return clipboard; }
    public void setClipboard(ClipboardProps clipboard) { this.clipboard = clipboard; }

    public CleanupProps getCleanup() { return cleanup; }
    public void setCleanup(CleanupProps cleanup) { this.cleanup = cleanup; }

    public RateLimitProps getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimitProps rateLimit) { this.rateLimit = rateLimit; }

    public TelegramProps getTelegram() { return telegram; }
    public void setTelegram(TelegramProps telegram) { this.telegram = telegram; }

    public static class ClipboardProps {
        private int idLength = 8;
        private int ttlHours = 24;
        private long maxTextSizeBytes = 10_485_760;
        private long maxFileSizeBytes = 524_288_000;
        private int chunkSizeBytes = 1_048_576;      // 1 MB
        private int minChunkSizeBytes = 262_144;       // 256 KB
        private int maxChunkSizeBytes = 5_242_880;      // 5 MB

        public int getIdLength() { return idLength; }
        public void setIdLength(int idLength) { this.idLength = idLength; }
        public int getTtlHours() { return ttlHours; }
        public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
        public long getMaxTextSizeBytes() { return maxTextSizeBytes; }
        public void setMaxTextSizeBytes(long maxTextSizeBytes) { this.maxTextSizeBytes = maxTextSizeBytes; }
        public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
        public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
        public int getChunkSizeBytes() { return chunkSizeBytes; }
        public void setChunkSizeBytes(int chunkSizeBytes) { this.chunkSizeBytes = chunkSizeBytes; }
        public int getMinChunkSizeBytes() { return minChunkSizeBytes; }
        public void setMinChunkSizeBytes(int minChunkSizeBytes) { this.minChunkSizeBytes = minChunkSizeBytes; }
        public int getMaxChunkSizeBytes() { return maxChunkSizeBytes; }
        public void setMaxChunkSizeBytes(int maxChunkSizeBytes) { this.maxChunkSizeBytes = maxChunkSizeBytes; }
    }

    public static class CleanupProps {
        private int intervalSeconds = 300;
        private int transferTimeoutSeconds = 600;
        private boolean hardDeleteEnabled = true;
        private int hardDeleteDelayHours = 168; // 7 days

        public int getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }
        public int getTransferTimeoutSeconds() { return transferTimeoutSeconds; }
        public void setTransferTimeoutSeconds(int transferTimeoutSeconds) { this.transferTimeoutSeconds = transferTimeoutSeconds; }
        public boolean isHardDeleteEnabled() { return hardDeleteEnabled; }
        public void setHardDeleteEnabled(boolean hardDeleteEnabled) { this.hardDeleteEnabled = hardDeleteEnabled; }
        public int getHardDeleteDelayHours() { return hardDeleteDelayHours; }
        public void setHardDeleteDelayHours(int hardDeleteDelayHours) { this.hardDeleteDelayHours = hardDeleteDelayHours; }
    }

    public static class RateLimitProps {
        private int maxCreatesPerMinute = 10;
        private int maxOpensPerMinute = 30;

        public int getMaxCreatesPerMinute() { return maxCreatesPerMinute; }
        public void setMaxCreatesPerMinute(int maxCreatesPerMinute) { this.maxCreatesPerMinute = maxCreatesPerMinute; }
        public int getMaxOpensPerMinute() { return maxOpensPerMinute; }
        public void setMaxOpensPerMinute(int maxOpensPerMinute) { this.maxOpensPerMinute = maxOpensPerMinute; }
    }

    public static class TelegramProps {
        private boolean enabled = false;
        private String botToken = "";
        private String webhookUrl = "";
        private String webhookSecret = "";
        private String reportCron = "0 0 9 * * *";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }

        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

        public String getReportCron() { return reportCron; }
        public void setReportCron(String reportCron) { this.reportCron = reportCron; }
    }
}
