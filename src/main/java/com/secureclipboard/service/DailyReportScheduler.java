package com.secureclipboard.service;

import com.secureclipboard.config.AppProperties;
import com.secureclipboard.repository.TelegramSubscriberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DailyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyReportScheduler.class);

    private final TelegramBotService botService;
    private final DailyReportService reportService;
    private final TelegramSubscriberRepository subscriberRepo;
    private final AppProperties appProps;

    public DailyReportScheduler(TelegramBotService botService,
                                 DailyReportService reportService,
                                 TelegramSubscriberRepository subscriberRepo,
                                 AppProperties appProps) {
        this.botService = botService;
        this.reportService = reportService;
        this.subscriberRepo = subscriberRepo;
        this.appProps = appProps;
    }

    @Scheduled(cron = "${app.telegram.report-cron:0 0 9 * * *}")
    public void sendDailyReport() {
        if (!appProps.getTelegram().isEnabled()) return;

        List<Long> chatIds = subscriberRepo.findAllChatIds();
        if (chatIds.isEmpty()) return;

        String report = reportService.buildReport();
        for (long chatId : chatIds) {
            botService.sendMessage(chatId, report);
        }

        log.info("Daily report sent to {} subscriber(s)", chatIds.size());
    }
}
