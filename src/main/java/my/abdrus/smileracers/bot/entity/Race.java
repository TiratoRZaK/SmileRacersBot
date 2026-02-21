package my.abdrus.smileracers.bot.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import my.abdrus.smileracers.bot.enumeration.BusterType;
import my.abdrus.smileracers.config.RaceProperties;

@Slf4j
public class Race {

    @Getter
    private final Match match;
    private final List<ConcurrentLinkedQueue<BusterType>> playerTickQueues;
    private final List<DoubleAdder> playerScores;
    private final List<AtomicInteger> playerShields;
    private final Double raceSize;
    private final Double stepSize;
    private final Double bustSize;
    private final Double slowSize;
    private final Integer replaceBustChance;
    private final Integer equalizeBustChance;
    private Integer topPaymentPlayerNumber = 0;
    private Integer bottomPaymentPlayerNumber = 0;

    public Race(Match match, RaceProperties properties,
                MatchPlayer topPaymentPlayer, MatchPlayer bottomPaymentPlayer) {
        this.match = match;
        this.raceSize = properties.getDefaultRaceSize();
        this.stepSize = properties.getDefaultStepSize();
        this.bustSize = properties.getDefaultBustSize();
        this.slowSize = properties.getDefaultSlowSize();
        this.replaceBustChance = properties.getReplaceBustChance();
        this.equalizeBustChance = properties.getReplaceBustChance();
        this.playerScores = new ArrayList<>();
        this.playerTickQueues = new ArrayList<>();
        this.playerShields = new ArrayList<>();

        if (topPaymentPlayer != null) {
            this.topPaymentPlayerNumber = topPaymentPlayer.getNumber();
        }
        if (bottomPaymentPlayer != null) {
            this.bottomPaymentPlayerNumber = bottomPaymentPlayer.getNumber();
        }

        List<MatchPlayer> matchPlayers = match.getMatchPlayers();
        matchPlayers.forEach(matchPlayer -> {
            playerTickQueues.add(new ConcurrentLinkedQueue<>());
            playerScores.add(new DoubleAdder());
            playerShields.add(new AtomicInteger(0));
        });
        generateDefaultTicks();
    }

    public boolean isNotFinish() {
        return playerScores.stream().noneMatch(score -> score.doubleValue() >= raceSize);
    }

    public void generateDefaultTicks() {
        playerTickQueues.forEach(playerTickQueue -> playerTickQueue.add(BusterType.NONE));
    }

    public void tick() {
        Random random = new Random();

        for (int i = 0; i < playerTickQueues.size(); i++) {
            BusterType tick = playerTickQueues.get(i).poll();
            if (random.nextInt(99) > replaceBustChance) {
                tick = random.nextBoolean() ? BusterType.BUST : BusterType.SLOW;
            }
            step(i, tick, random);
        }
    }

    public void step(Integer playerIndex, BusterType type, Random random) {
        double standardTickSize = random.nextDouble(stepSize);

        double additionalTickSize = getAdditionalTickSize(playerIndex, type);

        DoubleAdder score = playerScores.get(playerIndex);
        double tickSize = standardTickSize + additionalTickSize;
        double maxTickSize = raceSize - score.doubleValue();

        if (playerIndex + 1 == topPaymentPlayerNumber) {
            tickSize = tickSize - 0.25;
        } else if (playerIndex + 1 == bottomPaymentPlayerNumber) {
            tickSize = tickSize + 0.25;
        }

        double max = playerScores.stream().mapToDouble(DoubleAdder::doubleValue).max().getAsDouble();
        if (score.doubleValue() == max && random.nextInt(99) > equalizeBustChance) {
            tickSize = tickSize - bustSize;
        }

        double min = playerScores.stream().mapToDouble(DoubleAdder::doubleValue).min().getAsDouble();
        if (score.doubleValue() == min && random.nextInt(99) > equalizeBustChance) {
            tickSize = tickSize + bustSize;
        }

        if (tickSize > maxTickSize) {
            tickSize = maxTickSize;
        }

        score.add(tickSize);
    }

    private double getAdditionalTickSize(Integer playerIndex, BusterType type) {
        double additionalTickSize = 0d;
        if (type != null) {
            additionalTickSize = switch (type) {
                case BUST -> playerIndex + 1 == topPaymentPlayerNumber ? bustSize - 0.25 : bustSize;
                case SLOW -> {
                    AtomicInteger currentShield = playerShields.get(playerIndex);
                    if (currentShield.get() > 0) {
                        currentShield.decrementAndGet();
                        yield 0d;
                    } else {
                        yield playerIndex + 1 == topPaymentPlayerNumber ? -slowSize - 0.25 : -slowSize;
                    }
                }
                default -> 0d;
            };
        }
        return additionalTickSize;
    }

    public void addBusterForPlayer(Integer playerNumber, BusterType busterType) {
        if (busterType.equals(BusterType.SHIELD)) {
            playerShields.get(playerNumber - 1).addAndGet(5);
            return;
        }
        step(playerNumber - 1, busterType, new Random());
    }

    public String paintRace() {
        StringBuilder text = new StringBuilder();
        text
                .append("\uD83D\uDD25 Битва в самом разгаре! \uD83D\uDD25\n")
                .append("Помоги своему фавориту придти на 🏁 первым!\n")
                .append("\n")
                .append("Используй бустеры на кнопках ниже:\n")
                .append(" \uD83D\uDC07 (10⭐️) - временно ускоряет выбранный смайл\n")
                .append(" \uD83D\uDC22 (10⭐️) - временно замедляет выбранный смайл\n")
                .append(" \uD83E\uDE96 (40⭐️) - позволяет защититься от 5-ти \uD83D\uDC22 \n")
                .append("\n")
                .append("🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧\n");

        match.getMatchPlayers().forEach(matchPlayer -> {
            Double leftPoints = raceSize - playerScores.get(matchPlayer.getNumber() - 1).intValue();
            double completedPoints = raceSize - leftPoints;

            String race = ".".repeat(Math.max((int) completedPoints, 0)) + matchPlayer.getPlayerName() + ".".repeat(Math.max(leftPoints.intValue(), 0)) + "🏁\n";
            text.append(race);
        });

        text
                .append("🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧\n");
        return text.toString();
    }

    public String publicFinishRace() {
        StringBuilder text = new StringBuilder();

        match.getMatchPlayers()
                .stream()
                .max(Comparator.comparingDouble(
                        player -> playerScores.get(player.getNumber() - 1).doubleValue()
                ))
                .ifPresent(matchPlayer ->  {
                    match.setWinner(matchPlayer.getNumber());
                    text
                            .append("🏁 Гонка завершена! 🏁\n" + "Поздравляем победителя: ")
                            .append(matchPlayer.getPlayerName())
                            .append("!\n\n");
                });
        text
                .append("🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧")
                .append("\n");

        match.getMatchPlayers().forEach(matchPlayer -> {
            Double leftPoints = raceSize - playerScores.get(matchPlayer.getNumber() - 1).intValue();
            double completedPoints = raceSize - leftPoints;

            String race = ".".repeat((int) completedPoints) + matchPlayer.getPlayerName() + ".".repeat(leftPoints.intValue()) + "🏁\n";
            text.append(race);
        });

        text
                .append("🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧🚧")
                .append("\n");
        return text.toString();
    }
}