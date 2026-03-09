package my.abdrus.emojirace.bot.entity;

import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Data
@Table(name = "BALANCE_TOPUPS")
public class BalanceTopup {

    @Id
    @SequenceGenerator(name = "balance_topups_seq", sequenceName = "balance_topups_seq", allocationSize = 50)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "balance_topups_seq")
    private Long id;

    @Column(name = "USER_CHAT_ID", nullable = false)
    private Long userChatId;

    @Column(name = "SUM", nullable = false)
    private Long sum;

    @Column(name = "CREATED_DATE", nullable = false)
    private Date createdDate = new Date();

    @Column(name = "SOURCE", nullable = false)
    private String source;
}
