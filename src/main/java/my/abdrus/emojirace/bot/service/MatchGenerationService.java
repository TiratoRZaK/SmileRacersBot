package my.abdrus.emojirace.bot.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

import lombok.extern.slf4j.Slf4j;
import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.entity.Match;
import my.abdrus.emojirace.bot.entity.Player;
import my.abdrus.emojirace.bot.enumeration.MatchStatus;
import my.abdrus.emojirace.bot.repository.MatchRepository;
import my.abdrus.emojirace.bot.repository.PlayerRepository;
import my.abdrus.emojirace.config.ChannelProperties;
import my.abdrus.emojirace.config.RaceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MatchGenerationService {

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
            var latestActiveMatch = matchRepository
                    .findFirstByStatusInOrderByCreatedDateDesc(List.of(MatchStatus.values()))
                    .orElse(null);
            if (latestActiveMatch == null || latestActiveMatch.getStatus().equals(MatchStatus.COMPLETED)) {
                latestActiveMatch = matchService.createMatchByPlayers(getPlayersForMatch());
                log.info("Генерация новой гонки #{}", latestActiveMatch.getId());
                matchRepository
                        .findById(latestActiveMatch.getId())
                        .ifPresent(match -> matchService.sendMatchLineToChannel(match, bot));
            } else {
                matchRepository
                        .findById(latestActiveMatch.getId())
                        .ifPresent(match -> matchService.startLiveByActiveMatch(match, bot));
            }
        }, Duration.ofMinutes(3));
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
