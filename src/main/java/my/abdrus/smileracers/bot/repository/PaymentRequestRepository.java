package my.abdrus.smileracers.bot.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import my.abdrus.smileracers.bot.entity.Match;
import my.abdrus.smileracers.bot.entity.MatchPlayer;
import my.abdrus.smileracers.bot.entity.PaymentRequest;
import my.abdrus.smileracers.bot.enumeration.PaymentRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, UUID> {

    @Transactional
    @Modifying
    @Query("""
            UPDATE PaymentRequest r
            SET r.status = 'PAYED', r.payedDate = CURRENT_TIMESTAMP
            WHERE r.id = :id
            """)
    void setPayedStatus(@Param("id") UUID id);

    @Modifying(clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE PaymentRequest r
            SET r.status = 'COMPLETED'
            WHERE r.matchPlayer.match = :match AND r.matchPlayer <> :winner
            """)
    void completeLoseRequests(@Param("winner") MatchPlayer winner,
                              @Param("match") Match match);

    List<PaymentRequest> findAllByMatchPlayerAndStatus(@Param("matchPlayer") MatchPlayer matchPlayer,
                                                       @Param("status") PaymentRequestStatus status);

    List<PaymentRequest> findAllByMatchPlayerAndStatusAndUserChatId(@Param("matchPlayer") MatchPlayer matchPlayer,
                                                                    @Param("status") PaymentRequestStatus status,
                                                                    @Param("userChatId") Long userChatId);
}
