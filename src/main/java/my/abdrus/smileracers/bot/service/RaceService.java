package my.abdrus.smileracers.bot.service;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledFuture;

import my.abdrus.smileracers.bot.entity.Race;
import my.abdrus.smileracers.bot.enumeration.BusterType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
public class RaceService {

    @Autowired
    private TaskScheduler scheduler;

    private ScheduledFuture<?> timerFuture;

    private Race activeRace;

    public void startRace(Race race) {
        activeRace = race;
        timerFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (race.isNotFinish()) {
                race.tick();
                race.generateDefaultTicks();
            } else {
                timerFuture.cancel(false);
                activeRace = null;
            }
        }, Duration.of(2, ChronoUnit.SECONDS));
    }

    public void addTickForPlayer(Integer playerNumber, BusterType busterType) {
        if (activeRace != null) {
            activeRace.addBusterForPlayer(playerNumber, busterType);
        }
    }

    public Race getActiveRace() {
        return activeRace;
    }
}
