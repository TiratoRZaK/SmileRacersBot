package my.abdrus.emojirace.api.dto;

import lombok.Data;

@Data
public class BoostRequestDto {
    private Integer playerNumber;
    private String type;
}