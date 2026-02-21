package my.abdrus.smileracers.bot.repository;

import java.util.UUID;

import my.abdrus.smileracers.bot.entity.MatchPlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MatchPlayerRepository extends JpaRepository<MatchPlayer, UUID> {
}