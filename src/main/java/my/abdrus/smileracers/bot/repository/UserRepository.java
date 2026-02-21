package my.abdrus.smileracers.bot.repository;

import java.util.Optional;
import java.util.UUID;

import my.abdrus.smileracers.bot.entity.BotUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<BotUser, UUID> {

    Optional<BotUser> findByUserChatId(Long userChatId);

    boolean existsByUserChatIdAndIsAdminTrue(Long userChatId);
}