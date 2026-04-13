package my.abdrus.emojirace.bot.repository;

import java.util.Optional;
import java.util.UUID;

import my.abdrus.emojirace.bot.entity.BotUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<BotUser, UUID> {

    Optional<BotUser> findByUserChatId(Long userChatId);

    Optional<BotUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    Optional<BotUser> findFirstByUserChatIdLessThanOrderByUserChatIdAsc(Long userChatId);

    boolean existsByUserChatIdAndIsAdminTrue(Long userChatId);

    @Query("select lower(u.username) from BotUser u where u.username is not null and u.username <> '' order by lower(u.username) asc")
    java.util.List<String> findAllUsernames();
}
