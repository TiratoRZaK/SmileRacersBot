package my.abdrus.smileracers.bot.service;

import jakarta.validation.constraints.NotNull;
import my.abdrus.smileracers.bot.PaymentBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;

@Service
public class MainChannelService extends ChannelService {

    @Value("${telegram.bot.channel.main.chatId}")
    private String mainChannelChatId;

    public Long getId() {
        return Long.parseLong(mainChannelChatId);
    }

    /**
     * Проверить что обновление пришло в основной канал.
     */
    public boolean isMainChannel(@NotNull Update update) {
        return update.hasChannelPost()
                && getId().equals(update.getChannelPost().getChatId());
    }

    @Override
    public void updateProcess(Update update, PaymentBot bot) {
        if (update.hasCallbackQuery()) {
            callbackQueryProcess(update.getCallbackQuery(), bot);
        }
    }

    @Override
    public boolean callbackQueryProcess(CallbackQuery callbackQuery, PaymentBot bot) {
        boolean isProcessed = super.callbackQueryProcess(callbackQuery, bot);
        if (isProcessed) {
            return true;
        }
        return true;
    }
}