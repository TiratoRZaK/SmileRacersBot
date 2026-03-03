package my.abdrus.emojirace.bot.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@NoArgsConstructor
@Table(name = "MATCH_PLAYERS")
public class MatchPlayer {

    @Id
    @Column(name = "ID", nullable = false, unique = true)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JoinColumn(name = "PLAYER_NAME", nullable = false, referencedColumnName = "name")
    @ManyToOne
    private Player player;

    @JoinColumn(name = "MATCH_ID", referencedColumnName = "id")
    @ManyToOne
    private Match match;

    @Column(name = "NUMBER", nullable = false)
    private Integer number;

    @Column(name = "SCORE", nullable = false)
    private Long score = 0L;

    public MatchPlayer(Player player, Integer playerNumber) {
        this.player = player;
        this.number = playerNumber;
    }

    @Transient
    public String getPlayerName() {
        return getPlayer().getName();
    }

    @Override
    public String toString() {
        return "Матч #" + match.getId() + " Игрок " + player.getName() + " #" + number;
    }
}