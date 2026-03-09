package my.abdrus.emojirace.bot.entity;

import java.util.Date;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import my.abdrus.emojirace.bot.enumeration.PaymentRequestStatus;

/**
 * Голос за участника гонки.
 */
@Entity
@Data
@Table(name = "PAYMENT_REQUESTS")
public class PaymentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JoinColumn(name = "MATCH_PLAYER_ID", nullable = false)
    @ManyToOne(optional = false)
    private MatchPlayer matchPlayer;

    @Column(name = "USER_CHAT_ID", nullable = false)
    private Long userChatId;

    @Column(name = "SUM", nullable = false)
    private Long sum = 0L;

    @Column(name = "CREATED_DATE")
    private Date createdDate = new Date();

    @Column(name = "PAYED_DATE")
    private Date payedDate;

    @Column(name = "TO_WINNER", nullable = false)
    private boolean toWinner = false;

    @Column(name = "STATUS", nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentRequestStatus status = PaymentRequestStatus.WAIT_PAYMENT;
}
