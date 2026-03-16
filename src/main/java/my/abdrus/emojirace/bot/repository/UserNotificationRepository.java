package my.abdrus.emojirace.bot.repository;

import java.util.List;
import java.util.Optional;

import my.abdrus.emojirace.bot.entity.UserNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    List<UserNotification> findTop100ByUserChatIdOrderByCreatedDateDesc(Long userChatId);

    Optional<UserNotification> findByIdAndUserChatId(Long id, Long userChatId);

    List<UserNotification> findAllByUserChatId(Long userChatId);
}
