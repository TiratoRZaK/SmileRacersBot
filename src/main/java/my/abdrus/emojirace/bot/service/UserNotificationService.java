package my.abdrus.emojirace.bot.service;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import my.abdrus.emojirace.bot.entity.UserNotification;
import my.abdrus.emojirace.bot.repository.UserNotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserNotificationService {

    private final UserNotificationRepository userNotificationRepository;

    public void save(Long userChatId, String text, Integer messageId) {
        if (userChatId == null || text == null || text.isBlank()) {
            return;
        }
        UserNotification notification = new UserNotification();
        notification.setUserChatId(userChatId);
        notification.setText(text.trim());
        notification.setMessageId(messageId);
        userNotificationRepository.save(notification);
    }

    public void create(Long userChatId, String text) {
        save(userChatId, text, null);
    }

    public List<UserNotification> getRecent(Long userChatId) {
        if (userChatId == null) {
            return List.of();
        }
        return userNotificationRepository.findTop100ByUserChatIdOrderByCreatedDateDesc(userChatId);
    }

    public Optional<UserNotification> findByIdAndUserId(Long id, Long userChatId) {
        if (id == null || userChatId == null) {
            return Optional.empty();
        }
        return userNotificationRepository.findByIdAndUserChatId(id, userChatId);
    }

    public void delete(UserNotification notification) {
        if (notification == null) {
            return;
        }
        userNotificationRepository.delete(notification);
    }

    public List<UserNotification> getAllByUserId(Long userChatId) {
        if (userChatId == null) {
            return List.of();
        }
        return userNotificationRepository.findAllByUserChatId(userChatId);
    }

    public void deleteAll(List<UserNotification> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return;
        }
        userNotificationRepository.deleteAll(notifications);
    }

    @Transactional
    public void deleteByUserChatIdAndMessageId(Long userChatId, Integer messageId) {
        if (userChatId == null || messageId == null) {
            return;
        }
        userNotificationRepository.deleteByUserChatIdAndMessageId(userChatId, messageId);
    }
}
