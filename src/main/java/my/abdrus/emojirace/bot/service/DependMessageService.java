package my.abdrus.emojirace.bot.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.entity.DependMessageCode;
import my.abdrus.emojirace.bot.enumeration.DependMessage;
import org.springframework.stereotype.Service;

@Service
public class DependMessageService {

    private final ConcurrentHashMap<Long, List<DependMessage>> messageMap = new ConcurrentHashMap<>();

    public void putDependMessage(Long chatId, DependMessage... messages) {
        var dependMessages = messageMap.getOrDefault(chatId, new ArrayList<>());
        dependMessages.addAll(Arrays.asList(messages));
        messageMap.put(chatId, dependMessages);
    }

    public void deleteDependMessage(Long chatId, DependMessageCode code, EmojiRaceBot bot) {
        var dependMessages = messageMap.getOrDefault(chatId, new ArrayList<>());
        List<DependMessage> forRemove = dependMessages.stream()
                .filter(dependMessage -> dependMessage.getCode().equals(code))
                .peek(dependMessage -> {
                    try {
                        bot.deleteMessage(chatId, dependMessage.getMessageId());
                    } catch (Exception ignore) { }
                })
                .toList();
        dependMessages.removeAll(forRemove);
        messageMap.put(chatId, dependMessages);
    }
}
