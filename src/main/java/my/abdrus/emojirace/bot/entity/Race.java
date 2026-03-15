package my.abdrus.emojirace.bot.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import my.abdrus.emojirace.bot.enumeration.BusterType;
import my.abdrus.emojirace.bot.enumeration.MatchType;
import my.abdrus.emojirace.config.RaceProperties;

@Slf4j
public class Race {

    @Getter
    @Setter
    private Match match;
    private final List<ConcurrentLinkedDeque<BusterType>> playerTickQueues;
    private final List<DoubleAdder> playerScores;
    private final List<AtomicInteger> playerShields;
    private final List<BusterType> lastAppliedBusters;
    @Getter
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
        this.lastAppliedBusters = new ArrayList<>();

        if (topPaymentPlayer != null) {
            this.topPaymentPlayerNumber = topPaymentPlayer.getNumber();
        }
        if (bottomPaymentPlayer != null) {
            this.bottomPaymentPlayerNumber = bottomPaymentPlayer.getNumber();
        }

        List<MatchPlayer> matchPlayers = match.getMatchPlayers();
        matchPlayers.forEach(matchPlayer -> {
            playerTickQueues.add(new ConcurrentLinkedDeque<>());
            playerScores.add(new DoubleAdder());
            playerShields.add(new AtomicInteger(0));
            lastAppliedBusters.add(BusterType.NONE);
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

        double maxScore = playerScores.stream().mapToDouble(DoubleAdder::doubleValue).max().getAsDouble();
        double minScore = playerScores.stream().mapToDouble(DoubleAdder::doubleValue).min().getAsDouble();

        for (int i = 0; i < playerTickQueues.size(); i++) {
            var busterType = playerTickQueues.get(i).pop();
            if (BusterType.NONE.equals(busterType) && random.nextInt(99) > replaceBustChance) {
                busterType = random.nextBoolean() ? BusterType.BUST : BusterType.SLOW;
            }
            BusterType tickBusterType = step(i, busterType, random, minScore, maxScore);
            lastAppliedBusters.set(i, tickBusterType);
        }
    }

    public BusterType step(Integer playerIndex, BusterType busterType, Random random, double minScore, double maxScore) {
        double standardTickSize = random.nextDouble(stepSize);

        var appliedBooster = new AtomicInteger(0);
        double additionalTickSize = getAdditionalTickSize(playerIndex, busterType, appliedBooster);
        BusterType resultBusterType = resolveInitialBoosterType(busterType, appliedBooster.get());

        DoubleAdder score = playerScores.get(playerIndex);
        double tickSize = standardTickSize + additionalTickSize;
        double maxTickSize = raceSize - score.doubleValue();

        if (isTopPaymentPlayerNumber(playerIndex + 1)) {
            tickSize = tickSize - 0.25;
        } else if (isBottomPaymentPlayerNumber(playerIndex + 1)) {
            tickSize = tickSize + 0.25;
        }

        if (score.doubleValue() == maxScore && random.nextInt(99) > equalizeBustChance) {
            tickSize = tickSize - bustSize;
            resultBusterType = BusterType.SLOW;
        }

        if (score.doubleValue() == minScore && random.nextInt(99) > equalizeBustChance) {
            tickSize = tickSize + bustSize;
            resultBusterType = BusterType.BUST;
        }

        if (tickSize > maxTickSize) {
            tickSize = maxTickSize;
        }

        score.add(tickSize);
        return resultBusterType;
    }

    private BusterType resolveInitialBoosterType(BusterType busterType, int appliedBooster) {
        if (appliedBooster == 1) {
            return BusterType.SHIELD;
        }
        if (appliedBooster == 2) {
            return BusterType.NONE;
        }
        return busterType == null ? BusterType.NONE : busterType;
    }

    private double getAdditionalTickSize(Integer playerIndex, BusterType busterType, AtomicInteger appliedBooster) {
        double additionalTickSize = 0d;
        if (busterType != null) {
            additionalTickSize = switch (busterType) {
                case BUST -> isTopPaymentPlayerNumber(playerIndex + 1)
                        ? bustSize - 0.25
                        : bustSize;
                case SLOW -> {
                    var currentShieldCount = playerShields.get(playerIndex);
                    if (currentShieldCount.get() > 0) {
                        currentShieldCount.decrementAndGet();
                        appliedBooster.set(2);
                        yield 0d;
                    } else {
                        yield isTopPaymentPlayerNumber(playerIndex + 1)
                                ? -slowSize - 0.25
                                : -slowSize;
                    }
                }
                case SHIELD -> {
                    appliedBooster.set(1);
                    yield 0d;
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
        playerTickQueues.get(playerNumber - 1).push(busterType);
    }

    public String getRaceStateMessage() {
        StringBuilder text = new StringBuilder();
        String eventName = match.getType() == MatchType.BATTLE ? "Батл" : "Гонка";
        text
                .append("\uD83D\uDD25 ").append(eventName).append(" №").append(match.getId()).append(" в самом разгаре! \uD83D\uDD25\n")
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

    public String getCompletedRaceStateMessage() {
        StringBuilder text = new StringBuilder();

        match.getMatchPlayers()
                .stream()
                .max(Comparator.comparingDouble(player -> getScoreByNumber(player.getNumber())))
                .ifPresent(matchPlayer ->  {
                    match.setWinner(matchPlayer.getNumber());
                    String eventName = match.getType() == MatchType.BATTLE ? "Батл" : "Гонка";
                    text.append("🏁 ").append(eventName).append(" №")
                            .append(match.getId())
                            .append(" завершен").append(match.getType() == MatchType.BATTLE ? "" : "а").append("! 🏁\n")
                            .append("Поздравляем победителя: ")
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

    public Double getScoreByNumber(Integer number) {
        return playerScores.get(number - 1).doubleValue();
    }

    public Integer getShieldsByNumber(Integer number) {
        return playerShields.get(number - 1).get();
    }

    public BusterType getLastAppliedBusterByNumber(Integer number) {
        return lastAppliedBusters.get(number - 1);
    }

    private boolean isTopPaymentPlayerNumber(int number) {
        return Objects.equals(topPaymentPlayerNumber, number);
    }

    private boolean isBottomPaymentPlayerNumber(int number) {
        return Objects.equals(bottomPaymentPlayerNumber, number);
    }
}
