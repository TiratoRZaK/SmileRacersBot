package my.abdrus.emojirace.bot.repository;

import java.util.List;
import java.util.UUID;

import my.abdrus.emojirace.bot.entity.Match;
import my.abdrus.emojirace.bot.entity.MatchPlayer;
import my.abdrus.emojirace.bot.entity.PaymentRequest;
import my.abdrus.emojirace.bot.enumeration.PaymentRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис для работы с голосами.
 */
@Repository
public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, UUID> {

    /**
     * Подтвердить оплату голоса.
     */
    @Transactional
    @Modifying
    @Query("""
            UPDATE PaymentRequest r
            SET r.status = 'PAYED', r.payedDate = CURRENT_TIMESTAMP
            WHERE r.id = :id
            """)
    void setPayedStatus(@Param("id") UUID id);

    /**
     * Устастановить завершённый статус всем проигравшим.
     */
    @Modifying(clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE PaymentRequest r
            SET r.status = 'COMPLETED', r.toWinner = false 
            WHERE r.matchPlayer.match = :match AND r.matchPlayer <> :winner
            """)
    void completeLoseRequests(@Param("winner") MatchPlayer winner,
                              @Param("match") Match match);

    /**
     * Найти все голоса за участника гонки в определённом статусе.
     */
    List<PaymentRequest> findAllByMatchPlayerAndStatus(@Param("matchPlayer") MatchPlayer matchPlayer,
                                                       @Param("status") PaymentRequestStatus status);

    /**
     * Найти все голоса конкретного пользователя за участника гонки в определённом статусе.
     */
    List<PaymentRequest> findAllByMatchPlayerAndStatusAndUserChatId(@Param("matchPlayer") MatchPlayer matchPlayer,
                                                                    @Param("status") PaymentRequestStatus status,
                                                                    @Param("userChatId") Long userChatId);

    @Query("""
            SELECT COALESCE(SUM(r.sum), 0)
            FROM PaymentRequest r
            WHERE r.matchPlayer = :matchPlayer
              AND r.userChatId = :userChatId
              AND r.status IN ('PAYED', 'COMPLETED')
            """)
    Long sumMyVotesByMatchPlayer(@Param("matchPlayer") MatchPlayer matchPlayer,
                                 @Param("userChatId") Long userChatId);

    /**
     * Сумма оплаченных голосов (PAYED/COMPLETED) по батлу.
     */
    @Query("""
            SELECT COALESCE(SUM(r.sum), 0)
            FROM PaymentRequest r
            WHERE r.matchPlayer.match = :match
              AND r.status IN ('PAYED', 'COMPLETED')
            """)
    Long sumBattleBank(@Param("match") Match match);

    List<PaymentRequest> findAllByUserChatIdOrderByCreatedDateDesc(Long userChatId);

    void deleteAllByMatchPlayer(MatchPlayer matchPlayer);
}
