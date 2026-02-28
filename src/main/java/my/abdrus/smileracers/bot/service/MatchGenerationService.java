package my.abdrus.smileracers.bot.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import lombok.extern.slf4j.Slf4j;
import my.abdrus.smileracers.bot.SmileRacersBot;
import my.abdrus.smileracers.bot.entity.Player;
import my.abdrus.smileracers.bot.repository.PlayerRepository;
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

    private final ConcurrentLinkedQueue<Player> favoritePlayersQueue = new ConcurrentLinkedQueue<>();

    public int addPlayerToQueue(Player player) {
        favoritePlayersQueue.add(player);
        return favoritePlayersQueue.size() - 1;
    }

    public List<Player> getPlayersForMatch(int count, List<Player> excludePlayers) {
        List<Player> players = playerRepository.findAll();
        players.removeAll(excludePlayers);
        Collections.shuffle(players);

        return players.subList(0, Math.min(count, players.size()));
    }

    public void startGeneration(Long chatId, SmileRacersBot bot) {
        taskScheduler.scheduleWithFixedDelay(() -> {
            log.info("Запуск генерации матча");
            List<Player> queuedPlayers = new ArrayList<>();
            while (queuedPlayers.size() != 4) {
                Player player = favoritePlayersQueue.poll();
                if (player == null) {
                    break;
                }
                if (!queuedPlayers.contains(player)) {
                    queuedPlayers.add(player);
                }
            }

            List<Player> playersForMatch = getPlayersForMatch(4 - queuedPlayers.size(), queuedPlayers);
            playersForMatch.addAll(queuedPlayers);
            matchService.createMatchByPlayerNames(playersForMatch.stream().map(Player::getName).toList().toArray(new String[0]));
            log.info("Запуск линии матча");
            matchService.sendLineByActiveMatch(chatId, true, bot);
            try {
                Thread.sleep(30_000);
                log.info("Запуск лайва матча");
                matchService.startLiveByActiveMatch(chatId, bot);
            } catch (InterruptedException e) {
                log.error("Ошибка генерации матча.", e);
            }
        }, Duration.ofMinutes(5));
    }
}
