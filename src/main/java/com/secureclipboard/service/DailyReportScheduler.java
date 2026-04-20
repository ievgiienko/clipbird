package com.secureclipboard.service;

import com.secureclipboard.repository.TelegramSubscriberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "app.telegram.enabled", havingValue = "true")
public class DailyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyReportScheduler.class);

    private final ClipbirdTelegramBot botService;
    private final DailyReportService reportService;
    private final TelegramSubscriberRepository subscriberRepo;

    public DailyReportScheduler(ClipbirdTelegramBot bot,
                                 DailyReportService reportService,
                                 TelegramSubscriberRepository subscriberRepo) {
        this.botService = bot;
        this.reportService = reportService;
        this.subscriberRepo = subscriberRepo;
    }

    @Scheduled(cron = "${app.telegram.report-cron:0 0 9 * * *}")
    public void sendDailyReport() {
        List<Long> chatIds = subscriberRepo.findAllChatIds();
        if (chatIds.isEmpty()) return;

        String report = reportService.buildReport();
        for (long chatId : chatIds) {
            botService.sendMessage(chatId, report);
        }

        log.info("Daily report sent to {} subscriber(s)", chatIds.size());
    }
}
