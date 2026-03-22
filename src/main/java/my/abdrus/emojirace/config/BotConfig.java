package my.abdrus.emojirace.config;

import org.apache.http.client.config.RequestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestTemplate;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Configuration
public class BotConfig {

    private static final Logger log = LoggerFactory.getLogger(BotConfig.class);

    private static final int TELEGRAM_CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int TELEGRAM_CONNECTION_REQUEST_TIMEOUT_MILLIS = 3_000;
    private static final int TELEGRAM_SOCKET_TIMEOUT_MILLIS = 10_000;

    @Bean
    public TelegramBotsApi telegramBotsApi() throws Exception {
        return new TelegramBotsApi(DefaultBotSession.class);
    }

    @Bean
    public DefaultBotOptions defaultBotOptions() {
        DefaultBotOptions options = new DefaultBotOptions();
        options.setRequestConfig(RequestConfig.custom()
                .setConnectTimeout(TELEGRAM_CONNECT_TIMEOUT_MILLIS)
                .setConnectionRequestTimeout(TELEGRAM_CONNECTION_REQUEST_TIMEOUT_MILLIS)
                .setSocketTimeout(TELEGRAM_SOCKET_TIMEOUT_MILLIS)
                .build());
        return options;
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("emoji-race-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(error -> log.error("Unhandled scheduler error", error));
        return scheduler;
    }
}
