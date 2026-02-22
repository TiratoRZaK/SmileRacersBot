package my.abdrus.smileracers.bot.service;

import java.util.List;

import my.abdrus.smileracers.bot.SmileRacersBot;
import my.abdrus.smileracers.bot.entity.Match;
import my.abdrus.smileracers.bot.entity.MatchPlayer;
import my.abdrus.smileracers.bot.entity.PaymentRequest;
import my.abdrus.smileracers.bot.entity.Player;
import my.abdrus.smileracers.bot.enumeration.BusterType;
import my.abdrus.smileracers.bot.exception.PaymentException;
import my.abdrus.smileracers.bot.repository.MatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import static my.abdrus.smileracers.bot.enumeration.BusterType.BUST;
import static my.abdrus.smileracers.bot.enumeration.BusterType.SHIELD;
import static my.abdrus.smileracers.bot.enumeration.BusterType.SLOW;

@Service
public abstract class ChannelService {

    @Value("${telegram.bot.channel.adminMode}")
    private boolean adminMode;

    @Autowired
    protected MatchRepository matchRepository;
    @Autowired
    protected RaceService raceService;
    @Autowired
    protected MatchService matchService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private UserService userService;

    public abstract void updateProcess(Update update, SmileRacersBot bot);

    public boolean callbackQueryProcess(CallbackQuery callbackQuery, SmileRacersBot bot) {
        String query = callbackQuery.getData();
        Long userChatId = callbackQuery.getFrom().getId();

        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQuery.getId());
        answer.setShowAlert(false);

        if (query.startsWith("vote_")) {
            String[] s = query.split("_");
            var playerNumber = Integer.parseInt(s[1]);
            var matchId = Long.valueOf(s[2]);
            var matchOptional = matchRepository.findById(matchId);
            if (matchOptional.isEmpty()) {
                return true;
            }
            var match = matchOptional.get();
            var player = match.getPlayerByNumber(playerNumber);

            Integer messageId = sendStarsRequest(userChatId, player, bot);
            matchService.createScoreMessage(match, userChatId, messageId);
            bot.execute(answer);
            return true;
        }
        if (query.startsWith("bust_")) {
            String[] s = query.split("_");
            Integer playerNumber = Integer.parseInt(s[1]);
            busterPaymentProcess(callbackQuery, playerNumber, answer, BUST, bot);
            bot.execute(answer);
            return true;
        } else if (query.startsWith("slow_")) {
            String[] s = query.split("_");
            Integer playerNumber = Integer.parseInt(s[1]);
            busterPaymentProcess(callbackQuery, playerNumber, answer, SLOW, bot);
            bot.execute(answer);
            return true;
        } else if (query.startsWith("shield_")) {
            String[] s = query.split("_");
            Integer playerNumber = Integer.parseInt(s[1]);
            busterPaymentProcess(callbackQuery, playerNumber, answer, SHIELD, bot);
            bot.execute(answer);
            return true;
        }
        return false;
    }

    private void busterPaymentProcess(CallbackQuery callbackQuery, Integer playerNumber, AnswerCallbackQuery answer, BusterType busterType, SmileRacersBot bot) {
        Long userChatId = callbackQuery.getFrom().getId();
        String chatId = callbackQuery.getMessage().getChatId().toString();

        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setUserChatId(userChatId);
        paymentRequest.setSum(busterType.getCost());

        boolean isAdmin = userService.isAdminOrCreatorForChannel(chatId, userChatId, bot);

        try {
            if (!isAdmin || !adminMode) {
                accountService.pay(paymentRequest);
            }
            raceService.addTickForPlayer(playerNumber, busterType);
            answer.setText("\uD83C\uDF89  Бустер " + busterType.getName() + " активирован! \uD83C\uDF89");
        } catch (PaymentException e) {
            answer.setShowAlert(true);
            answer.setText(String.format("Оплата не прошла. \n%s\nОбратитесь к владельцу канала.", e.getMessage()));
        }
    }

    public Integer sendStarsRequest(Long userChatId, MatchPlayer matchPlayer, SmileRacersBot bot) {
        var message = new SendMessage();
        Match match = matchPlayer.getMatch();
        Player player = matchPlayer.getPlayer();

        message.setChatId(userChatId);
        message.setText("❓Думаешь победит " + player.getName() + " ❓\n" +
                "\n" +
                "⭐ Насколько звёзд ты уверен? ⭐");

        var markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(
                List.of(createBuyButton(1L, match.getId(), matchPlayer.getNumber()),
                        createBuyButton(5L, match.getId(), matchPlayer.getNumber()),
                        createBuyButton(10L, match.getId(), matchPlayer.getNumber())),
                List.of(createBuyButton(25L, match.getId(), matchPlayer.getNumber()),
                        createBuyButton(50L, match.getId(), matchPlayer.getNumber()),
                        createBuyButton(100L, match.getId(), matchPlayer.getNumber())),
                List.of(createBuyButton(250L, match.getId(), matchPlayer.getNumber()),
                        createBuyButton(500L, match.getId(), matchPlayer.getNumber()),
                        createBuyButton(1000L, match.getId(), matchPlayer.getNumber())),
                List.of(createBuyButton(2500L, match.getId(), matchPlayer.getNumber()),
                        createBuyButton(5000L, match.getId(), matchPlayer.getNumber()),
                        createBuyButton(10000L, match.getId(), matchPlayer.getNumber())),
                List.of(matchService.createMatchLinkButton(match))
        ));

        message.setReplyMarkup(markup);
        return bot.execute(message).getMessageId();
    }

    private InlineKeyboardButton createBuyButton(long amount, long matchId, Integer playerNumber) {
        var button = new InlineKeyboardButton("⭐ " + amount);
        button.setCallbackData("buy_" + amount + "_" + matchId + "_" + playerNumber);
        return button;
    }
}
