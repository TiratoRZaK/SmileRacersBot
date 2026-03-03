package my.abdrus.emojirace.bot.entity;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "PLAYERS")
public class Player {

    @Id
    @Column(name = "NAME", unique = true, nullable = false)
    private String name;

    @Column(name = "WIN_COUNT")
    private Long winCount = 0L;

    @Column(name = "MATCH_COUNT")
    private Long matchCount = 0L;

    public Player(String name) {
        this.name = name;
        this.winCount = 0L;
        this.matchCount = 0L;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Player player = (Player) o;
        return Objects.equals(name, player.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }
}

