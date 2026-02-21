package my.abdrus.smileracers.bot.repository;

import java.util.List;
import java.util.Optional;

import my.abdrus.smileracers.bot.entity.Match;
import my.abdrus.smileracers.bot.enumeration.MatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {

    @Query("""
    SELECT m
    FROM Match m
    LEFT JOIN FETCH m.matchPlayers
    WHERE m.id = :id
""")
    Optional<Match> findById(@Param("id") Long id);

    @Query("""
    SELECT m
    FROM Match m
    LEFT JOIN FETCH m.matchPlayers
    WHERE m.createdDate = (
        SELECT MAX(m2.createdDate)
        FROM Match m2
        WHERE m2.status in :statusList
    )
""")
    Match findLatestActiveMatchWithPlayers(List<MatchStatus> statusList);
}