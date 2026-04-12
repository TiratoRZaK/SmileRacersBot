package my.abdrus.emojirace.bot.repository;

import java.util.Optional;
import java.util.UUID;

import my.abdrus.emojirace.bot.entity.BotUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<BotUser, UUID> {

    Optional<BotUser> findByUserChatId(Long userChatId);

    Optional<BotUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    Optional<BotUser> findFirstByUserChatIdLessThanOrderByUserChatIdAsc();

    boolean existsByUserChatIdAndIsAdminTrue(Long userChatId);
}
