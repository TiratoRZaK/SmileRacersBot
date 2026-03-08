package my.abdrus.emojirace.bot.entity;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import my.abdrus.emojirace.bot.enumeration.MatchStatus;
import my.abdrus.emojirace.bot.enumeration.MatchType;

@Data
@Builder
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "MATCHES")
public class Match {

    @Id
    @Column(name = "ID", nullable = false, unique = true)
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(name = "CREATED_DATE", nullable = false)
    private Date createdDate;

    @Column(name = "TIMER_MESSAGE_ID")
    private Integer channelTimerMessageId;

    @Column(name = "WINNER_ID")
    private Integer winner;

    @Column(name = "STATUS")
    @Enumerated(EnumType.STRING)
    private MatchStatus status;

    @Column(name = "TYPE", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private MatchType type = MatchType.REGULAR;

    @Column(name = "CREATOR_USER_CHAT_ID")
    private Long creatorUserChatId;

    @OneToMany(mappedBy = "match", cascade = CascadeType.REMOVE)
    private List<ScoreMessage> scoreMessages;

    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL)
    private List<MatchPlayer> matchPlayers;

    public MatchPlayer getPlayerByNumber(Integer playerNumber) {
        if (playerNumber == null) {
            return null;
        }
        return matchPlayers.stream()
                .filter(p -> playerNumber.equals(p.getNumber()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public String toString() {
        return String.format("Матч №%s от %s", id, createdDate);
    }

    public MatchPlayer getRandomPlayerByBet(Match match, boolean findLowest) {
        Map<MatchPlayer, Long> playerSums = match.getMatchPlayers().stream()
                .collect(Collectors.toMap(mP -> mP, MatchPlayer::getScore));

        if (playerSums.isEmpty()) return null;

        long targetSum = playerSums.values().stream()
                .reduce(findLowest ? Long::min : Long::max)
                .orElse(0L);

        List<MatchPlayer> targetPlayers = playerSums.entrySet().stream()
                .filter(entry -> entry.getValue() == targetSum)
                .map(Map.Entry::getKey)
                .toList();

        if (targetPlayers.isEmpty()) {
            return null;
        } else if (targetPlayers.size() == 1) {
            return targetPlayers.get(0);
        } else {
            return targetPlayers.get(new Random().nextInt(targetPlayers.size()));
        }
    }
}
