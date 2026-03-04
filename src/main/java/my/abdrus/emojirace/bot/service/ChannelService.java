package my.abdrus.emojirace.bot.service;

import java.util.List;

import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.entity.Account;
import my.abdrus.emojirace.bot.entity.Match;
import my.abdrus.emojirace.bot.entity.MatchPlayer;
import my.abdrus.emojirace.bot.entity.PaymentRequest;
import my.abdrus.emojirace.bot.entity.Player;
import my.abdrus.emojirace.bot.enumeration.BusterType;
import my.abdrus.emojirace.bot.exception.PaymentException;
import my.abdrus.emojirace.bot.repository.AccountRepository;
import my.abdrus.emojirace.bot.repository.MatchRepository;
import my.abdrus.emojirace.bot.repository.PaymentRequestRepository;
import my.abdrus.emojirace.bot.repository.PlayerRepository;
import my.abdrus.emojirace.bot.repository.UserRepository;
import my.abdrus.emojirace.config.ChannelProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import static my.abdrus.emojirace.bot.enumeration.BusterType.BUST;
import static my.abdrus.emojirace.bot.enumeration.BusterType.SHIELD;
import static my.abdrus.emojirace.bot.enumeration.BusterType.SLOW;

@Service
public abstract class ChannelService {

    @Autowired
    protected MatchRepository matchRepository;
    @Autowired
    protected RaceService raceService;
    @Autowired
    protected MatchService matchService;
    @Autowired
    protected AccountService accountService;
    @Autowired
    protected AccountRepository accountRepository;
    @Autowired
    protected UserService userService;
    @Autowired
    protected ChannelProperties channelProperties;

    public abstract void updateProcess(Update update, EmojiRaceBot bot);

    public boolean callbackQueryProcess(CallbackQuery callbackQuery, EmojiRaceBot bot) {
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

            if (!userService.checkExists(userChatId)) {
                answer.setShowAlert(true);
                answer.setText("Вы у нас впервые! \uD83E\uDD29 \nНеобходимо создать аккаунт. \uD83C\uDF8A \nПерейдите в бота \uD83E\uDD16 кнопкой ниже, иначе мы не сможем вам ответить \uD83D\uDE22");
                bot.execute(answer);
                return true;
            }
            Integer messageId = sendStarsRequest(userChatId, player, bot);
            matchService.createScoreMessage(match, userChatId, messageId);
            if (userChatId.equals(channelProperties.getMainChannelChatId())) {
                answer.setText("Перейдите в бота для выбора суммы ⭐\uFE0F для голоса.");
            }
            bot.execute(answer);
            return true;
        }
        if (query.startsWith("bust_")) {
            if (!userService.checkExists(userChatId)) {
                answer.setShowAlert(true);
                answer.setText("Вы у нас впервые! \uD83E\uDD29 \nНеобходимо создать аккаунт. \uD83C\uDF8A \nПерейдите в бота \uD83E\uDD16 кнопкой ниже, иначе мы не сможем вам ответить \uD83D\uDE22");
                bot.execute(answer);
                return true;
            }
            String[] s = query.split("_");
            Integer playerNumber = Integer.parseInt(s[1]);
            busterPaymentProcess(callbackQuery, playerNumber, answer, BUST, bot);
            bot.execute(answer);
            return true;
        } else if (query.startsWith("slow_")) {
            if (!userService.checkExists(userChatId)) {
                answer.setShowAlert(true);
                answer.setText("Вы у нас впервые! \uD83E\uDD29 \nНеобходимо создать аккаунт. \uD83C\uDF8A \nПерейдите в бота \uD83E\uDD16 кнопкой ниже, иначе мы не сможем вам ответить \uD83D\uDE22");
                bot.execute(answer);
                return true;
            }
            String[] s = query.split("_");
            Integer playerNumber = Integer.parseInt(s[1]);
            busterPaymentProcess(callbackQuery, playerNumber, answer, SLOW, bot);
            bot.execute(answer);
            return true;
        } else if (query.startsWith("shield_")) {
            if (!userService.checkExists(userChatId)) {
                answer.setShowAlert(true);
                answer.setText("Вы у нас впервые! \uD83E\uDD29 \nНеобходимо создать аккаунт. \uD83C\uDF8A \nПерейдите в бота \uD83E\uDD16 кнопкой ниже, иначе мы не сможем вам ответить \uD83D\uDE22");
                bot.execute(answer);
                return true;
            }
            String[] s = query.split("_");
            Integer playerNumber = Integer.parseInt(s[1]);
            busterPaymentProcess(callbackQuery, playerNumber, answer, SHIELD, bot);
            bot.execute(answer);
            return true;
        }
        return false;
    }

    private void busterPaymentProcess(CallbackQuery callbackQuery, Integer playerNumber, AnswerCallbackQuery answer, BusterType busterType, EmojiRaceBot bot) {
        Long userChatId = callbackQuery.getFrom().getId();
        String chatId = callbackQuery.getMessage().getChatId().toString();

        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setUserChatId(userChatId);
        paymentRequest.setSum(busterType.getCost());

        boolean isAdmin = userService.isAdminOrCreatorForChannel(chatId, userChatId, bot);

        boolean hasFreeBust = false;

        try {
            Account account = accountService.getByUserId(userChatId);
            userService.addInfoIfNeed(callbackQuery.getFrom());
            if (account.getFreeBustCount() > 0) {
                account.setFreeBustCount(account.getFreeBustCount() - 1);
                accountRepository.save(account);
                hasFreeBust = true;
            } else if (!isAdmin || !channelProperties.isAdminMode()) {
                accountService.pay(paymentRequest);
            }
            raceService.addTickForPlayer(playerNumber, busterType);
            if (hasFreeBust) {
                answer.setText("\uD83C\uDF89  Бесплатный бустер " + busterType.getName() + " активирован! \uD83C\uDF89");
            } else {
                answer.setText("\uD83C\uDF89  Бустер " + busterType.getName() + " активирован! \uD83C\uDF89");
            }
        } catch (PaymentException e) {
            answer.setShowAlert(true);
            answer.setText(String.format("Оплата не прошла. \n%s", e.getMessage()));
        }
    }

    public Integer sendStarsRequest(Long userChatId, MatchPlayer matchPlayer, EmojiRaceBot bot) {
        var message = new SendMessage();
        Match match = matchPlayer.getMatch();
        Player player = matchPlayer.getPlayer();

        message.setChatId(userChatId);
        message.setText("❓Думаешь в гонке №" + match.getId() + " победит " + player.getName() + " ❓\n" +
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
