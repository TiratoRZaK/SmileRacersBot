package my.abdrus.emojirace.config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.RequiredArgsConstructor;
import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.service.JackpotService;
import my.abdrus.emojirace.bot.service.MatchGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;

@Component
@RequiredArgsConstructor
public class BotStartupService {

    private static final Logger log = LoggerFactory.getLogger(BotStartupService.class);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final TelegramBotsApi telegramBotsApi;
    private final EmojiRaceBot emojiRaceBot;
    private final TaskScheduler taskScheduler;
    private final JackpotService jackpotService;
    private final MatchGenerationService matchGenerationService;

    private final AtomicBoolean registrationScheduled = new AtomicBoolean(false);
    private final AtomicBoolean botRegistered = new AtomicBoolean(false);
    private final AtomicBoolean backgroundStarted = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        scheduleStartup(Duration.ZERO);
    }

    private void scheduleStartup(Duration delay) {
        if (!registrationScheduled.compareAndSet(false, true)) {
            return;
        }
        taskScheduler.schedule(this::registerAndStart, Instant.now().plus(delay));
    }

    private void registerAndStart() {
        registrationScheduled.set(false);
        try {
            if (!botRegistered.get()) {
                telegramBotsApi.registerBot(emojiRaceBot);
                botRegistered.set(true);
                log.info("Telegram bot registered successfully.");
            }

            if (!backgroundStarted.get()) {
                jackpotService.createIfNeedToChannel(emojiRaceBot);
                matchGenerationService.startGeneration(emojiRaceBot);
                backgroundStarted.set(true);
                log.info("Telegram-dependent background tasks started.");
            }
        } catch (Exception e) {
            log.warn("Telegram is unavailable during startup. Bot registration/init will retry in {} sec. Cause: {}",
                    RETRY_DELAY.toSeconds(), e.getMessage());
            scheduleStartup(RETRY_DELAY);
        }
    }
}
