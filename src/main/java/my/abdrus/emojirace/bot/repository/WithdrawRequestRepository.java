package my.abdrus.emojirace.bot.repository;

import java.util.UUID;

import my.abdrus.emojirace.bot.entity.WithdrawRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис для работы с выводами.
 */
@Repository
public interface WithdrawRequestRepository extends JpaRepository<WithdrawRequest, Long> {

    /**
     * Подтвердить оплату вывода.
     */
    @Transactional
    @Modifying
    @Query("""
            UPDATE PaymentRequest r
            SET r.status = 'PAYED', r.payedDate = CURRENT_TIMESTAMP
            WHERE r.id = :id
            """)
    void setPayedStatus(@Param("id") Long id);
}
