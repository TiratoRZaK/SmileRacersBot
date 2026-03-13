package my.abdrus.emojirace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "telegram.bot")
@Getter
@Setter
public class BotProperties {

    private String username;
    private String token;
    private boolean localTestModeEnabled;
    private Long localTestModeUserId;
}
