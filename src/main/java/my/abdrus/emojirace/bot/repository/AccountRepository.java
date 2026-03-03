package my.abdrus.emojirace.bot.repository;

import java.util.Optional;
import java.util.UUID;

import my.abdrus.emojirace.bot.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Query("""
            SELECT a
            FROM Account a
            WHERE a.userChatId = :userChatId AND a.balance >= :amount
            """)
    Optional<Account> findAvailableAccount(@Param("userChatId") Long userChatId,
                                           @Param("amount") Long amount);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRED)
    @Query("UPDATE Account a SET a.balance = a.balance - :amount WHERE a.id = :accountId AND a.balance >= :amount")
    int withdrawFunds(@Param("accountId") UUID accountId,
                      @Param("amount") Long amount);

    Optional<Account> findByUserChatId(@Param("userChatId") Long userChatId);
}