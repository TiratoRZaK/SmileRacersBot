package my.abdrus.emojirace.bot.repository;

import java.util.List;

import my.abdrus.emojirace.bot.entity.BalanceTopup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BalanceTopupRepository extends JpaRepository<BalanceTopup, Long> {

    List<BalanceTopup> findAllByUserChatIdOrderByCreatedDateDesc(Long userChatId);
}
