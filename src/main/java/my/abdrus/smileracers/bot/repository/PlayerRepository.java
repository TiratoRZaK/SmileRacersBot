package my.abdrus.smileracers.bot.repository;

import my.abdrus.smileracers.bot.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlayerRepository extends JpaRepository<Player, String> {

    Optional<Player> findByName(String name);
}