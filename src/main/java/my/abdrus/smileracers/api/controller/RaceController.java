package my.abdrus.smileracers.api.controller;

import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import my.abdrus.smileracers.api.dto.BoostRequestDto;
import my.abdrus.smileracers.api.dto.RaceDto;
import my.abdrus.smileracers.api.dto.RacerDto;
import my.abdrus.smileracers.bot.entity.Race;
import my.abdrus.smileracers.bot.enumeration.BusterType;
import my.abdrus.smileracers.bot.service.RaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RaceController {

    @Autowired
    private RaceService raceService;

    @GetMapping("/race/current")
    public RaceDto getRace() {
        Race activeRace = raceService.getActiveRace();
        if (activeRace == null) {
            return new RaceDto();
        }
        return toDto(activeRace);
    }

    private RaceDto toDto(Race race) {
        if (race == null) {
            return new RaceDto();
        }
        RaceDto raceDto = new RaceDto();
        raceDto.setRacers(race.getMatch().getMatchPlayers().stream()
                .map(matchPlayer -> {
                    RacerDto racerDto = new RacerDto();
                    racerDto.setPlayerName(matchPlayer.getPlayerName());
                    racerDto.setScore(race.getScoreByNumber(matchPlayer.getNumber()));
                    racerDto.setPlayerNumber(matchPlayer.getNumber());
                    return racerDto;
                })
                .collect(Collectors.toList()));
        return raceDto;
    }

    @PostMapping("/boost")
    public void boost(@RequestBody BoostRequestDto req) {
        if (req == null || req.getPlayerNumber() == null || req.getType() == null) {
            return;
        }
        raceService.addTickForPlayer(req.getPlayerNumber(), BusterType.valueOf(req.getType()));
    }
}