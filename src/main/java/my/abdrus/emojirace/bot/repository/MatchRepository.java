package my.abdrus.emojirace.bot.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import my.abdrus.emojirace.bot.entity.Match;
import my.abdrus.emojirace.bot.enumeration.MatchStatus;
import my.abdrus.emojirace.bot.enumeration.MatchType;
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
    List<Match> findTop10ByStatusInOrderByCreatedDateDesc(Collection<MatchStatus> statusList);

    Optional<Match> findFirstByStatusOrderByCreatedDateAsc(MatchStatus status);

    Optional<Match> findFirstByStatusAndTypeOrderByCreatedDateAsc(MatchStatus status, MatchType type);

    Optional<Match> findFirstByStatusAndTypeAndBattleStartRequestedTrueOrderByCreatedDateAsc(MatchStatus status, MatchType type);

    boolean existsByTypeAndStatusAndMatchPlayers_OwnerUserChatId(MatchType type, MatchStatus status, Long ownerUserChatId);

    Optional<Match> findFirstByTypeAndStatusAndCreatorUserChatIdOrderByCreatedDateDesc(MatchType type, MatchStatus status, Long creatorUserChatId);

    Optional<Match> findFirstByTypeAndCreatorUserChatIdAndStatusInOrderByCreatedDateDesc(MatchType type, Long creatorUserChatId, Collection<MatchStatus> statuses);

    Optional<Match> findFirstByTypeAndStatusInAndMatchPlayers_OwnerUserChatIdOrderByCreatedDateDesc(MatchType type, Collection<MatchStatus> statuses, Long ownerUserChatId);

    List<Match> findTop5ByStatusOrderByCreatedDateDesc(MatchStatus status);
}
