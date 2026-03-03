package my.abdrus.emojirace.bot.repository;

import java.util.List;
import java.util.UUID;

import my.abdrus.emojirace.bot.entity.Match;
import my.abdrus.emojirace.bot.entity.ScoreMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ScoreMessageRepository extends JpaRepository<ScoreMessage, UUID> {

    @Query("select sm from ScoreMessage sm where sm.match = :match")
    List<ScoreMessage> findByMatch(@Param("match") Match match);
}