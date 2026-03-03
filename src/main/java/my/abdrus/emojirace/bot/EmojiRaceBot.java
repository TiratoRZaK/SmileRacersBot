package my.abdrus.emojirace.bot;

import java.io.Serializable;
import java.util.Date;
import java.util.Locale;

import jakarta.annotation.PostConstruct;
import my.abdrus.emojirace.bot.service.ClientChannelService;
import my.abdrus.emojirace.bot.service.JackpotService;
import my.abdrus.emojirace.bot.service.MainChannelService;
import my.abdrus.emojirace.bot.service.MatchGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

@Service
public class EmojiRaceBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(EmojiRaceBot.class);

    @Autowired
    private TaskScheduler scheduler;
    @Autowired
    private MainChannelService mainChannelService;
    @Autowired
    private ClientChannelService clientChannelService;
    @Autowired
    private MatchGenerationService matchGenerationService;
    @Autowired
    private JackpotService jackpotService;

    @Value("${telegram.bot.username}")
    private String username;

    @Value("${telegram.bot.token}")
    private String token;

    @Value("${telegram.bot.channel.defaultDeleteMessageDelay}")
    private String defaultDeleteMessageDelay;

    public Integer getDefaultDeleteMessageDelay() {
        return Integer.parseInt(defaultDeleteMessageDelay);
    }

    @PostConstruct
    public void start() {
        jackpotService.createIfNeedToChannel(this);
        matchGenerationService.startGeneration(mainChannelService.getId(), this);
    }

    @Override
    public void onUpdateReceived(Update update) {
        boolean isMainChannel = mainChannelService.isMainChannel(update);
        if (isMainChannel) {
            mainChannelService.updateProcess(update, this);
        } else {
            clientChannelService.updateProcess(update, this);
        }
    }

    public void deleteMessageScheduled(Long chatId, Integer messageId) {
        deleteMessageScheduled(chatId, messageId, getDefaultDeleteMessageDelay());
    }
    public void deleteMessageScheduled(Long chatId, Integer messageId, long delayMillis) {
        if (messageId == null) {
            return;
        }
        var deleteDate = new Date(System.currentTimeMillis() + delayMillis).toInstant();
        scheduler.schedule(() -> deleteMessage(chatId, messageId), deleteDate);
    }

    public void deleteMessage(Long chatId, Integer messageId) {
        execute(new DeleteMessage(chatId.toString(), messageId));
    }

    @Override
    public <T extends Serializable, Method extends BotApiMethod<T>> T execute(Method sendMessage) {
        final int maxAttempts = 2;
        int attempt = 1;
        while (attempt <= maxAttempts) {
            try {
                return super.execute(sendMessage);
            } catch (TelegramApiRequestException e) {
                if (isMessageNotModifiedError(e)) {
                    log.debug("Пропуск обновления сообщения без изменений");
                    return null;
                }
                if (isDeleteMessageNotExistsError(e)) {
                    log.debug("Пропуск удаления ранее удалённого сообщения");
                    return null;
                }

                Integer code = e.getErrorCode();
                boolean isTooManyRequests = code != null && code == 429;
                Integer retryAfterSec = e.getParameters() == null ? null : e.getParameters().getRetryAfter();
                if (!isTooManyRequests || retryAfterSec == null || attempt == maxAttempts) {
                    throw new RuntimeException(e);
                }

                long retryAfterMillis = retryAfterSec * 1_000L;
                log.error("Ошибка отправки. Telegram вернул 429, повтор через {} сек.", retryAfterSec);
                try {
                    Thread.sleep(retryAfterMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
                log.error("Повтор отправки");
                attempt++;
            } catch (TelegramApiException ex) {
                throw new RuntimeException(ex);
            }
        }
        throw new IllegalStateException("Unexpected bot execute state");
    }

    private boolean isMessageNotModifiedError(TelegramApiRequestException e) {
        Integer code = e.getErrorCode();
        if (code == null || code != 400) {
            return false;
        }

        String errorMessage = e.getApiResponse();
        if (errorMessage == null) {
            errorMessage = e.getMessage();
        }
        return errorMessage != null && errorMessage.toLowerCase(Locale.ROOT).contains("message is not modified");
    }

    private boolean isDeleteMessageNotExistsError(TelegramApiRequestException e) {
        Integer code = e.getErrorCode();
        if (code == null || code != 400) {
            return false;
        }

        String errorMessage = e.getApiResponse();
        if (errorMessage == null) {
            errorMessage = e.getMessage();
        }
        return errorMessage != null && errorMessage.toLowerCase(Locale.ROOT).contains("message to delete not found");
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public String getBotToken() {
        return token;
    }
}
