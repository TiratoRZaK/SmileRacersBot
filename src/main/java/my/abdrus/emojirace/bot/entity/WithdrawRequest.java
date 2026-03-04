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
import lombok.Builder;
import lombok.Data;
import my.abdrus.emojirace.bot.enumeration.PaymentRequestStatus;
import my.abdrus.emojirace.bot.enumeration.WithdrawRequestStatus;

/**
 * Запрос на вывод.
 */
@Entity
@Builder
@Data
@Table(name = "WITHDRAW_REQUESTS")
public class WithdrawRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(name = "CREATED_DATE")
    private Date createdDate = new Date();

    @Column(name = "USER_CHAT_ID", nullable = false)
    private Long userChatId;

    @Column(name = "SUM", nullable = false)
    private Long sum = 0L;

    @Column(name = "PAYED_DATE")
    private Date payedDate;

    @Column(name = "STATUS", nullable = false)
    @Enumerated(EnumType.STRING)
    private WithdrawRequestStatus status = WithdrawRequestStatus.CREATED;
}
