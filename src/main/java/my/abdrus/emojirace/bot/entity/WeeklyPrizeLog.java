package my.abdrus.emojirace.bot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Date;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@Table(name = "WEEKLY_PRIZE_LOGS")
public class WeeklyPrizeLog {

    @Id
    @SequenceGenerator(name = "weekly_prize_logs_seq", sequenceName = "weekly_prize_logs_seq", allocationSize = 50)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "weekly_prize_logs_seq")
    @Column(name = "ID", nullable = false, unique = true)
    private Long id;

    @Column(name = "WEEK_START", nullable = false, unique = true)
    private LocalDate weekStart;

    @Column(name = "WINNER_USER_CHAT_ID", nullable = false)
    private Long winnerUserChatId;

    @Column(name = "PRIZE_STARS", nullable = false)
    private Long prizeStars;

    @Column(name = "PRIZE_BOOSTERS", nullable = false)
    private Integer prizeBoosters;

    @Column(name = "CREATED_DATE", nullable = false)
    private Date createdDate = new Date();
}
