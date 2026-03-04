package my.abdrus.emojirace.bot.repository;

import my.abdrus.emojirace.bot.entity.Player;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public interface PlayerRepository extends JpaRepository<Player, String> {

    Optional<Player> findByName(String name);

    List<Player> findAllByNameNotIn(Collection<String> excludedNames);

    default List<Player> findAllExcept(Collection<Player> excludedPlayers) {

        if (excludedPlayers == null || excludedPlayers.isEmpty()) {
            return findAll();
        }

        return findAllByNameNotIn(excludedPlayers.stream().map(Player::getName).collect(Collectors.toSet()));
    }
}