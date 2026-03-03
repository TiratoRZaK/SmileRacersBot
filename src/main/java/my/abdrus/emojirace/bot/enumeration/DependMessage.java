package my.abdrus.emojirace.bot.enumeration;

import lombok.Builder;
import lombok.Data;
import my.abdrus.emojirace.bot.entity.DependMessageCode;

@Builder
@Data
public class DependMessage {

    private Integer messageId;
    private Long chatId;
    private DependMessageCode code;
}
