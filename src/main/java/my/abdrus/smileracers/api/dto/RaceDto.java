package my.abdrus.smileracers.api.dto;

import java.util.List;

import lombok.Data;

@Data
public class RaceDto {
    private List<RacerDto> racers;
}