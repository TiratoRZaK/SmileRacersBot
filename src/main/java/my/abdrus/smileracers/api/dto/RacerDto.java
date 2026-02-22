package my.abdrus.smileracers.api.dto;

import lombok.Data;

@Data
public class RacerDto {
    private Integer playerNumber;
    private String playerName;
    private Double score;
}