package my.abdrus.emojirace.bot.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

import lombok.extern.slf4j.Slf4j;
import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.entity.Match;
import my.abdrus.emojirace.bot.entity.Player;
import my.abdrus.emojirace.bot.enumeration.MatchStatus;
import my.abdrus.emojirace.bot.enumeration.MatchType;
import my.abdrus.emojirace.bot.repository.MatchRepository;
import my.abdrus.emojirace.bot.repository.PlayerRepository;
import my.abdrus.emojirace.config.RaceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MatchGenerationService {
    private static final Duration LIVE_MATCH_STUCK_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration CREATED_MATCH_STUCK_TIMEOUT = Duration.ofMinutes(10);

    @Autowired
    private PlayerRepository playerRepository;
    @Autowired
    private MatchService matchService;
    @Autowired
    private TaskScheduler taskScheduler;
    @Autowired
    private RaceProperties raceProperties;
    @Autowired
    private MatchRepository matchRepository;

    private final ConcurrentLinkedDeque<Player> favoritePlayersQueue = new ConcurrentLinkedDeque<>();

    public int addPlayerToQueue(Player player) {
        favoritePlayersQueue.add(player);
        return favoritePlayersQueue.size() - 1;
    }

    public void startGeneration(EmojiRaceBot bot) {
        taskScheduler.scheduleWithFixedDelay(() -> {
            try {
                var liveMatch = matchRepository.findFirstByStatusOrderByCreatedDateAsc(MatchStatus.LIVE).orElse(null);
                if (liveMatch != null) {
                    var liveSince = liveMatch.getCreatedDate();
                    var isStuckLive = liveSince != null
                            && Date.from(liveSince.toInstant().plus(LIVE_MATCH_STUCK_TIMEOUT)).before(new Date());
                    if (isStuckLive) {
                        liveMatch.setStatus(MatchStatus.CREATED);
                        matchRepository.save(liveMatch);
                        log.warn("Матч #{} завис в LIVE дольше {} минут. Возвращён в CREATED.",
                                liveMatch.getId(), LIVE_MATCH_STUCK_TIMEOUT.toMinutes());
                    }
                    return;
                }

                var waitingBattle = matchRepository
                        .findFirstByStatusAndTypeAndBattleStartRequestedTrueOrderByCreatedDateAsc(MatchStatus.CREATED, MatchType.BATTLE)
                        .filter(match -> matchService.canStartBattle(match.getId(), match.getCreatorUserChatId()))
                        .orElse(null);
                if (waitingBattle != null) {
                    log.info("Старт батла #{} из очереди ожидания", waitingBattle.getId());
                    matchRepository.findById(waitingBattle.getId()).ifPresent(match -> matchService.startLiveByActiveMatch(match, bot));
                    return;
                }

                var waitingRegularMatch = matchRepository
                        .findFirstByStatusAndTypeOrderByCreatedDateAsc(MatchStatus.CREATED, MatchType.REGULAR)
                        .orElse(null);
                if (waitingRegularMatch != null) {
                    matchRepository.findById(waitingRegularMatch.getId()).ifPresent(match -> matchService.startLiveByActiveMatch(match, bot));
                    return;
                }

                var staleCreatedMatch = matchRepository
                        .findFirstByStatusOrderByCreatedDateAsc(MatchStatus.CREATED)
                        .filter(match -> {
                            Date createdDate = match.getCreatedDate();
                            return createdDate != null
                                    && Date.from(createdDate.toInstant().plus(CREATED_MATCH_STUCK_TIMEOUT)).before(new Date());
                        })
                        .orElse(null);
                if (staleCreatedMatch != null) {
                    boolean canStart = staleCreatedMatch.getType() != MatchType.BATTLE
                            || matchService.canStartBattle(staleCreatedMatch.getId(), staleCreatedMatch.getCreatorUserChatId());
                    if (canStart) {
                        log.warn("Матч #{} в CREATED дольше {} минут — запускаем принудительно.",
                                staleCreatedMatch.getId(), CREATED_MATCH_STUCK_TIMEOUT.toMinutes());
                        matchRepository.findById(staleCreatedMatch.getId()).ifPresent(match -> matchService.startLiveByActiveMatch(match, bot));
                        return;
                    }
                }

                Match createdRegularMatch = matchService.createMatchByPlayers(getPlayersForMatch());
                log.info("Генерация новой гонки #{}", createdRegularMatch.getId());
                matchRepository
                        .findById(createdRegularMatch.getId())
                        .ifPresent(match -> matchService.sendMatchLineToChannel(match, bot));
            } catch (RuntimeException e) {
                log.error("Ошибка фоновой генерации гонки. Следующий цикл продолжит работу.", e);
            }
        }, Duration.ofMinutes(Math.max(1, raceProperties.getGenerationIntervalMinutes() == null
                ? 3
                : raceProperties.getGenerationIntervalMinutes())));
    }

    private List<Player> getPlayersForMatch() {
        int playerCount = raceProperties.getDefaultRacerCount();

        Set<Player> queuedPlayers = new LinkedHashSet<>();
        List<Player> deferredDuplicates = new ArrayList<>();

        int scanLimit = favoritePlayersQueue.size();

        while (queuedPlayers.size() < playerCount && scanLimit-- > 0) {
            Player p = favoritePlayersQueue.pollFirst();
            if (p == null) break;

            if (!queuedPlayers.add(p)) {
                deferredDuplicates.add(p);
            }
        }

        for (int i = deferredDuplicates.size() - 1; i >= 0; i--) {
            favoritePlayersQueue.addFirst(deferredDuplicates.get(i));
        }

        int need = playerCount - queuedPlayers.size();
        List<Player> additional = new ArrayList<>(playerRepository.findAllExcept(queuedPlayers));
        Collections.shuffle(additional);

        List<Player> result = new ArrayList<>(playerCount);
        result.addAll(queuedPlayers);
        result.addAll(additional.subList(0, need));
        return result;
    }
}
