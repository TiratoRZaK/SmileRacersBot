package my.abdrus.emojirace.bot.service;

import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.entity.BotUser;
import my.abdrus.emojirace.bot.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public boolean isAdminOrCreatorForChannel(String channelId, Long userChatId, EmojiRaceBot bot) {
        GetChatMember getChatMember = new GetChatMember(channelId, userChatId);
        ChatMember chatMember = bot.execute(getChatMember);
        String status = chatMember.getStatus();
        return status.equals("administrator") || status.equals("creator");
    }

    public BotUser addInfoIfNeed(User tgUser) {
        BotUser user = userRepository
                .findByUserChatId(tgUser.getId())
                .orElseGet(() -> createIfNeed(tgUser.getId()));

        user.setUsername(tgUser.getUserName());
        user.setFirstName(tgUser.getFirstName());
        user.setLastName(tgUser.getLastName());
        user.setUserChatId(tgUser.getId());
        return userRepository.save(user);
    }

    public BotUser createIfNeed(Long userId) {
        return userRepository
                .findByUserChatId(userId)
                .orElseGet(() -> {
                    BotUser user = new BotUser();
                    user.setUserChatId(userId);
                    return userRepository.save(user);
                });
    }

    public boolean isAdmin(Long userId) {
        return userRepository.existsByUserChatIdAndIsAdminTrue(userId);
    }

    public void setAdmin(Long userId) {
        if (!userRepository.existsByUserChatIdAndIsAdminTrue(userId)) {
            BotUser user = createIfNeed(userId);
            user.setAdmin(true);
            userRepository.save(user);
        }
    }

    public boolean checkExists(Long userChatId) {
        return userRepository.findByUserChatId(userChatId).isPresent();
    }

    public String getUsernameOrFallback(Long userChatId) {
        return userRepository.findByUserChatId(userChatId)
                .map(BotUser::getUsername)
                .filter(username -> username != null && !username.isBlank())
                .map(username -> "@" + username)
                .orElse("id:" + userChatId);
    }
}
