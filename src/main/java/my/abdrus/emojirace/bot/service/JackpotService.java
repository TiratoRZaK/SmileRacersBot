package my.abdrus.emojirace.bot.service;

import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.entity.Jackpot;
import my.abdrus.emojirace.bot.entity.Match;
import my.abdrus.emojirace.bot.entity.MatchPlayer;
import my.abdrus.emojirace.bot.repository.JackpotRepository;
import my.abdrus.emojirace.config.ChannelProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;

@Service
public class JackpotService {

    @Autowired
    private JackpotRepository jackpotRepository;
    @Autowired
    private ChannelProperties channelProperties;

    public void createIfNeedToChannel(EmojiRaceBot bot) {
        Long mainChannelChatId = channelProperties.getMainChannelChatId();
        Jackpot jackpot = jackpotRepository.findTopByIsPayedFalseOrderByCreatedDateDesc().orElseGet(() -> jackpotRepository.save(new Jackpot()));
        if (jackpot.getChannelTimerMessageId() == null) {
            SendMessage message = new SendMessage();
            message.setChatId(mainChannelChatId);
            message.setText("\uD83D\uDCB2\uD83D\uDCB2\uD83D\uDCB2   ДЖЕКПОТ " + jackpot.getSum() +" ⭐   \uD83D\uDCB2\uD83D\uDCB2\uD83D\uDCB2\n\n" +
                    "Пополняется с каждого матча. \n\n" +
                    "\uD83C\uDF40 Достанется случайному счастливчику. Может быть это будешь ты! \uD83C\uDF40");

            Message sentMessage = bot.execute(message);

            jackpot.setChannelTimerMessageId(sentMessage.getMessageId());
            jackpotRepository.save(jackpot);

            PinChatMessage pinMessage = new PinChatMessage();
            pinMessage.setChatId(mainChannelChatId);
            pinMessage.setMessageId(sentMessage.getMessageId());
            pinMessage.setDisableNotification(true);
            bot.execute(pinMessage);
        }
    }

    public void update(Match match, EmojiRaceBot bot) {
        Long mainChannelChatId = channelProperties.getMainChannelChatId();
        long sum = match.getMatchPlayers().stream().mapToLong(MatchPlayer::getScore).sum();
        if (sum == 0) {
            return;
        }
        Jackpot jackpot = jackpotRepository.findTopByIsPayedFalseOrderByCreatedDateDesc().orElseGet(() -> jackpotRepository.save(new Jackpot()));

        jackpot.setSum(jackpot.getSum() + 1);
        jackpot = jackpotRepository.save(jackpot);

        if (jackpot.getChannelTimerMessageId() != null) {
            EditMessageText message = new EditMessageText();
            message.setMessageId(jackpot.getChannelTimerMessageId());
            message.setChatId(mainChannelChatId);
            message.setText("\uD83D\uDCB2\uD83D\uDCB2\uD83D\uDCB2   ДЖЕКПОТ " + jackpot.getSum() +" ⭐   \uD83D\uDCB2\uD83D\uDCB2\uD83D\uDCB2\n\n" +
                    "Пополняется с каждого матча. \n\n" +
                    "\uD83C\uDF40 Достанется случайному счастливчику. Может быть это будешь ты! \uD83C\uDF40");
            bot.execute(message);
        }
    }
}
