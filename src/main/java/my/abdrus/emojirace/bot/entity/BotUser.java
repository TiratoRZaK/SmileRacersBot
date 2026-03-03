package my.abdrus.emojirace.bot.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@Table(name = "USERS")
public class BotUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "USER_CHAT_ID", nullable = false)
    private Long userChatId;

    @JoinColumn(name = "ACCOUNT_ID", unique = true)
    @OneToOne
    private Account account;

    @Column(name = "USERNAME")
    private String username;

    @JoinColumn(name = "FAVORITE_PLAYER_ID")
    @ManyToOne
    private Player favoritePlayer;

    @Column(name = "F_NAME")
    private String firstName;

    @Column(name = "L_NAME")
    private String lastName;

    @Column(name = "IS_ADMIN")
    private boolean isAdmin = false;
}
