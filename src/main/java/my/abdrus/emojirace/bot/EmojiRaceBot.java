package my.abdrus.emojirace.bot;

import java.io.Serializable;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Date;
import java.util.Locale;

import my.abdrus.emojirace.bot.service.ClientChannelService;
import my.abdrus.emojirace.bot.service.MainChannelService;
import my.abdrus.emojirace.bot.service.UserNotificationService;
import my.abdrus.emojirace.config.BotProperties;
import my.abdrus.emojirace.config.ChannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.DefaultBotOptions;
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

    private final TaskScheduler scheduler;
    private final MainChannelService mainChannelService;
    private final ClientChannelService clientChannelService;
    private final ChannelProperties channelProperties;
    private final BotProperties botProperties;
    private final UserNotificationService userNotificationService;

    public EmojiRaceBot(DefaultBotOptions botOptions,
                        TaskScheduler scheduler,
                        MainChannelService mainChannelService,
                        ClientChannelService clientChannelService,
                        ChannelProperties channelProperties,
                        BotProperties botProperties,
                        UserNotificationService userNotificationService) {
        super(botOptions, botProperties.getToken());
        this.scheduler = scheduler;
        this.mainChannelService = mainChannelService;
        this.clientChannelService = clientChannelService;
        this.channelProperties = channelProperties;
        this.botProperties = botProperties;
        this.userNotificationService = userNotificationService;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            boolean isMainChannel = mainChannelService.isMainChannel(update);
            if (isMainChannel) {
                mainChannelService.updateProcess(update, this);
            } else {
                clientChannelService.updateProcess(update, this);
            }
        } catch (RuntimeException e) {
            log.error("Ошибка обработки входящего Telegram update", e);
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
        userNotificationService.deleteByUserChatIdAndMessageId(chatId, messageId);
        try {
            execute(new DeleteMessage(chatId.toString(), messageId));
        } catch (RuntimeException e) {
            if (isIgnorableDeleteError(e)) {
                log.debug("Пропуск удаления сообщения {} в чате {}: {}", messageId, chatId, e.getMessage());
                return;
            }
            if (isTransientNetworkError(e)) {
                log.warn("Telegram недоступен при удалении сообщения {} в чате {}: {}", messageId, chatId, getRootCauseMessage(e));
                return;
            }
            throw e;
        }
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
                if (isTransientNetworkError(ex)) {
                    log.warn("Telegram API временно недоступен для метода {}: {}",
                            sendMessage.getClass().getSimpleName(), getRootCauseMessage(ex));
                }
                throw new RuntimeException(ex);
            }
        }
        throw new IllegalStateException("Unexpected bot execute state");
    }

    public <T extends Serializable, Method extends BotApiMethod<T>> void saveUserNotification(Method method, T response) {
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

    private boolean isIgnorableDeleteError(RuntimeException e) {
        Throwable cause = e.getCause();
        if (cause instanceof TelegramApiRequestException requestException) {
            if (isDeleteMessageNotExistsError(requestException)) {
                return true;
            }
            String errorMessage = requestException.getApiResponse();
            if (errorMessage == null || errorMessage.isBlank()) {
                errorMessage = requestException.getMessage();
            }
            if (errorMessage == null) {
                return false;
            }
            String normalizedMessage = errorMessage.toLowerCase(Locale.ROOT);
            return normalizedMessage.contains("message can't be deleted")
                    || normalizedMessage.contains("message cannot be deleted");
        }
        return false;
    }

    private boolean isTransientNetworkError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof NoRouteToHostException
                    || current instanceof ConnectException
                    || current instanceof SocketException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String getRootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
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
