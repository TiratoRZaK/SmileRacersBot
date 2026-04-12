package my.abdrus.emojirace.bot.repository;

import java.time.LocalDate;
import java.util.Optional;
import my.abdrus.emojirace.bot.entity.WeeklyPrizeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WeeklyPrizeLogRepository extends JpaRepository<WeeklyPrizeLog, Long> {
    boolean existsByWeekStart(LocalDate weekStart);
    Optional<WeeklyPrizeLog> findFirstByWeekStart(LocalDate weekStart);
}
