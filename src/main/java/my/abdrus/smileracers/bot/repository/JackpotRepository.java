package my.abdrus.smileracers.bot.repository;

import java.util.Optional;
import java.util.UUID;

import my.abdrus.smileracers.bot.entity.Jackpot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JackpotRepository extends JpaRepository<Jackpot, UUID> {

    Optional<Jackpot> findTopByIsPayedFalseOrderByCreatedDateDesc();
}