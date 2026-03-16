package my.abdrus.emojirace.bot;

import java.io.Serializable;
import java.util.Date;
import java.util.Locale;

import jakarta.annotation.PostConstruct;
import my.abdrus.emojirace.bot.service.ClientChannelService;
import my.abdrus.emojirace.bot.service.JackpotService;
import my.abdrus.emojirace.bot.service.MainChannelService;
import my.abdrus.emojirace.bot.service.MatchGenerationService;
import my.abdrus.emojirace.bot.service.UserNotificationService;
import my.abdrus.emojirace.config.BotProperties;
import my.abdrus.emojirace.config.ChannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
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
    @Autowired
    private ChannelProperties channelProperties;
    @Autowired
    private BotProperties botProperties;
    @Autowired
    private UserNotificationService userNotificationService;

    @PostConstruct
    public void start() {
        jackpotService.createIfNeedToChannel(this);
        matchGenerationService.startGeneration(this);
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
        deleteMessageScheduled(chatId, messageId, channelProperties.getDefaultDeleteMessageDelay());
    }
    public void deleteMessageScheduled(Long chatId, Integer messageId, long delayMillis) {
        if (messageId == null) {
            return;
        }
        userNotificationService.deleteByUserChatIdAndMessageId(chatId, messageId);
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
                T response = super.execute(sendMessage);
                saveUserNotification(sendMessage, response);
                return response;
            } catch (TelegramApiRequestException e) {
                if (isMessageNotModifiedError(e)) {
                    log.debug("Пропуск обновления сообщения без изменений");
                    return null;
                }
                if (isDeleteMessageNotExistsError(e)) {
                    log.debug("Пропуск удаления ранее удалённого сообщения");
                    return null;
                }
                if (isEditMessageNotExistsError(e)) {
                    log.debug("Пропуск изменения ранее удалённого сообщения");
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

    private <T extends Serializable, Method extends BotApiMethod<T>> void saveUserNotification(Method method, T response) {
        if (!(method instanceof SendMessage sendMessage) || !(response instanceof Message message)) {
            return;
        }
        Long chatId = message.getChatId();
        if (chatId == null || chatId <= 0) {
            return;
        }
        String text = sendMessage.getText();
        if (text == null || text.isBlank()) {
            text = message.getText();
        }
        userNotificationService.save(chatId, text, message.getMessageId());
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

    private boolean isEditMessageNotExistsError(TelegramApiRequestException e) {
        Integer code = e.getErrorCode();
        if (code == null || code != 400) {
            return false;
        }

        String errorMessage = e.getApiResponse();
        if (errorMessage == null) {
            errorMessage = e.getMessage();
        }
        return errorMessage != null && errorMessage.toLowerCase(Locale.ROOT).contains("message to edit not found");
    }

    @Override
    public String getBotUsername() {
        return botProperties.getUsername();
    }

    @Override
    public String getBotToken() {
        return botProperties.getToken();
    }
}
