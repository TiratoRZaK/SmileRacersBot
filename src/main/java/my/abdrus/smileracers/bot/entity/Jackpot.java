package my.abdrus.smileracers.bot.entity;

import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

@Data
@Entity
@Table(name = "JACKPOTS")
public class Jackpot {

    @Id
    @Column(name = "ID", nullable = false, unique = true)
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(name = "CREATED_DATE", nullable = false)
    @CreationTimestamp
    private Date createdDate;

    @Column(name = "TIMER_MESSAGE_ID")
    private Integer channelTimerMessageId;

    @Column(name = "SUM")
    private Long sum;

    @JoinColumn(name = "WINNER_ID")
    @ManyToOne
    private BotUser winner;

    @Column(name = "IS_PAYED")
    private Boolean isPayed;

    public Jackpot() {
        this.isPayed = false;
        this.sum = 0L;
    }
}