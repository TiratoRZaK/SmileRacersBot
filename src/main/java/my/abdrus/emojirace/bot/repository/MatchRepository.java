package my.abdrus.emojirace.bot.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import my.abdrus.emojirace.bot.entity.Match;
import my.abdrus.emojirace.bot.enumeration.MatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {

    @Query("""
        SELECT m
        FROM Match m
        LEFT JOIN FETCH m.matchPlayers
        WHERE m.id = :id
    """)
    Optional<Match> findById(@Param("id") Long id);

    Optional<Match> findFirstByStatusInOrderByCreatedDateDesc(Collection<MatchStatus> statusList);
}