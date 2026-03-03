package my.abdrus.emojirace.api.dto;

import java.util.List;

import lombok.Data;

@Data
public class RaceDto {
    private List<UnitDto> units;
}