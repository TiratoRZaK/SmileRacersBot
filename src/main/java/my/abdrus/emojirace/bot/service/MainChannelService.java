package my.abdrus.emojirace.bot.service;

import jakarta.validation.constraints.NotNull;
import my.abdrus.emojirace.bot.EmojiRaceBot;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;

@Service
public class MainChannelService extends ChannelService {

    /**
     * Проверить что обновление пришло в основной канал.
     */
    public boolean isMainChannel(@NotNull Update update) {
        if (update.hasChannelPost()) {
            return channelProperties.getMainChannelChatId().equals(update.getChannelPost().getChatId());
        }

        if (update.hasCallbackQuery()
                && update.getCallbackQuery().getMessage() != null
                && update.getCallbackQuery().getMessage().getChatId() != null) {
            return channelProperties.getMainChannelChatId()
                    .equals(update.getCallbackQuery().getMessage().getChatId());
        }

        return false;
    }

    @Override
    public void updateProcess(Update update, EmojiRaceBot bot) {
        if (update.hasCallbackQuery()) {
            callbackQueryProcess(update.getCallbackQuery(), bot);
        }
    }

    @Override
    public boolean callbackQueryProcess(CallbackQuery callbackQuery, EmojiRaceBot bot) {
        boolean isProcessed = super.callbackQueryProcess(callbackQuery, bot);
        if (isProcessed) {
            return true;
        }
        return true;
    }
}
