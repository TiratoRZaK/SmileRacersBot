package my.abdrus.emojirace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "telegram.bot.channel")
@Getter
@Setter
public class ChannelProperties {

    private Long mainChannelChatId;
    private Integer defaultDeleteMessageDelay;
    private Integer defaultDeleteMessageMenuDelay;
    private String helpLink;
    private String botLink;
    private String channelLink;
    private boolean adminMode;
}