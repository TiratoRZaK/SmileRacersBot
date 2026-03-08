package my.abdrus.emojirace.bot.util;

import my.abdrus.emojirace.bot.entity.BotUser;

public class TelegramUtils {

    public static String formatTelegramUser(BotUser user) {
        if (user == null) {
            return "неизвестный пользователь";
        }

        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return "@" + user.getUsername();
        }

        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            return user.getFirstName();
        }

        return String.valueOf(user.getId());
    }
}
