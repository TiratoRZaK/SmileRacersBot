package my.abdrus.emojirace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "telegram.bot.race")
@Getter
@Setter
public class RaceProperties {

    private Double defaultRaceSize;
    private Double defaultBustSize;
    private Double defaultSlowSize;
    private Double defaultStepSize;
    private Integer replaceBustChance;
    private Integer equalizeBustChance;
}