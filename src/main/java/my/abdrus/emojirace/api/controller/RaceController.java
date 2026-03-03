package my.abdrus.emojirace.api.controller;

import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import my.abdrus.emojirace.api.dto.BoostRequestDto;
import my.abdrus.emojirace.api.dto.RaceDto;
import my.abdrus.emojirace.api.dto.UnitDto;
import my.abdrus.emojirace.bot.entity.Race;
import my.abdrus.emojirace.bot.enumeration.BusterType;
import my.abdrus.emojirace.bot.service.RaceService;
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
        raceDto.setUnits(race.getMatch().getMatchPlayers().stream()
                .map(matchPlayer -> {
                    UnitDto unitDto = new UnitDto();
                    unitDto.setPlayerName(matchPlayer.getPlayerName());
                    unitDto.setScore(race.getScoreByNumber(matchPlayer.getNumber()));
                    unitDto.setPlayerNumber(matchPlayer.getNumber());
                    return unitDto;
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