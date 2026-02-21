package my.abdrus.smileracers.bot.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@Table(name = "ACCOUNTS")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "USER_CHAT_ID", nullable = false)
    private Long userChatId;

    @Column(name = "BALANCE", nullable = false)
    private Long balance = 0L;

    @JoinColumn(name = "USER_ID", unique = true)
    @OneToOne
    private BotUser user;

    public Account(Long userChatId, BotUser user) {
        this.userChatId = userChatId;
        this.user = user;
        this.balance = 0L;
    }
}
