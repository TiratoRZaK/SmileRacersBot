package my.abdrus.emojirace.bot.entity;

import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import my.abdrus.emojirace.bot.enumeration.WithdrawRequestStatus;

/**
 * Запрос на вывод.
 */
@Entity
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "WITHDRAW_REQUESTS")
public class WithdrawRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Builder.Default
    @Column(name = "CREATED_DATE")
    private Date createdDate = new Date();

    @Column(name = "USER_CHAT_ID", nullable = false)
    private Long userChatId;

    @Builder.Default
    @Column(name = "SUM", nullable = false)
    private Long sum = 0L;

    @Column(name = "PAYED_DATE")
    private Date payedDate;

    @Builder.Default
    @Column(name = "STATUS", nullable = false)
    @Enumerated(EnumType.STRING)
    private WithdrawRequestStatus status = WithdrawRequestStatus.CREATED;
}
