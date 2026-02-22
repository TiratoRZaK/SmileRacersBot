package my.abdrus.smileracers.api.dto;

import lombok.Data;

@Data
public class BoostRequestDto {
    private Integer playerNumber;
    private String type;
}