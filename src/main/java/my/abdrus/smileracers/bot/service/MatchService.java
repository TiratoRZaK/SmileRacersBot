package my.abdrus.smileracers.bot.service;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import my.abdrus.smileracers.bot.PaymentBot;
import my.abdrus.smileracers.bot.entity.Match;
import my.abdrus.smileracers.bot.entity.MatchPlayer;
import my.abdrus.smileracers.bot.entity.PaymentRequest;
import my.abdrus.smileracers.bot.entity.Player;
import my.abdrus.smileracers.bot.entity.Race;
import my.abdrus.smileracers.bot.entity.ScoreMessage;
import my.abdrus.smileracers.bot.enumeration.BusterType;
import my.abdrus.smileracers.bot.enumeration.MatchStatus;
import my.abdrus.smileracers.bot.enumeration.PaymentRequestStatus;
import my.abdrus.smileracers.bot.repository.MatchPlayerRepository;
import my.abdrus.smileracers.bot.repository.MatchRepository;
import my.abdrus.smileracers.bot.repository.PaymentRequestRepository;
import my.abdrus.smileracers.bot.repository.PlayerRepository;
import my.abdrus.smileracers.bot.repository.ScoreMessageRepository;
import my.abdrus.smileracers.config.RaceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Slf4j
@Service
public class MatchService {

    @Autowired
    private PlayerRepository playerRepository;
    @Autowired
    private MatchRepository matchRepository;
    @Autowired
    private MatchPlayerRepository matchPlayerRepository;
    @Autowired
    private ScoreMessageRepository scoreMessageRepository;
    @Autowired
    private RaceService raceService;
    @Autowired
    private JackpotService jackpotService;
    @Autowired
    private PaymentRequestRepository paymentRequestRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private RaceProperties raceProperties;

    @Value("${telegram.bot.link}")
    private String botLink;

    @Value("${telegram.channel.link}")
    private String channelLink;

    @Autowired
    private TaskScheduler scheduler;

    private ScheduledFuture<?> timerFuture;

    /**
     * Создать новый матч по именам игроков.
     */
    @Transactional
    public Match createMatchByPlayerNames(String... playerNames) {
        List<Player> players = Arrays.stream(playerNames)
                .map(playerName ->
                        playerRepository.findByName(playerName)
                                .orElseGet(() -> {
                                    Player newPlayer = new Player(playerName);
                                    return playerRepository.save(newPlayer);
                                }))
                .toList();

        List<MatchPlayer> matchPlayers = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            matchPlayers.add(new MatchPlayer(players.get(i), i + 1));
        }

        Match match = Match.builder()
                .createdDate(new Date())
                .status(MatchStatus.CREATED)
                .matchPlayers(matchPlayers)
                .build();

        matchPlayers.forEach(matchPlayer -> matchPlayer.setMatch(match));
        return matchRepository.save(match);
    }

    public Integer sendLineByActiveMatch(Long chatId, boolean isMainChannel, PaymentBot bot) {
        var match = matchRepository.findLatestActiveMatchWithPlayers(Arrays.asList(MatchStatus.values()));
        if (match == null) {
            return null;
        }

        switch (match.getStatus()) {
            case CREATED -> {
                String playerNames = match.getMatchPlayers().stream()
                        .map(MatchPlayer::getPlayerName)
                        .collect(Collectors.joining(" или "));

                var message = new SendMessage();

                message.setChatId(chatId.toString());
                String text = "❓❓❓   Кто победит?   ❓❓❓\n\n" +
                        playerNames +
                        "\n\n" +
                        "Решайся и голосуй звёздами!\n\n" +
                        "Пополнить баланс звёзд можно перейдя в бота.";
                message.setText(text);

                var markup = createActiveMatchToChannelForLineVotesKeyboard(match, isMainChannel);
                message.setReplyMarkup(markup);

                Integer messageId = bot.execute(message).getMessageId();
                createScoreMessage(match, chatId, messageId);
                return messageId;
            }
            case LIVE ->  {
                InlineKeyboardButton button = createMatchLinkButton(match);
                InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                        .keyboardRow(List.of(button))
                        .build();

                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());

                boolean isAdmin = userService.isAdmin(chatId);
                StringBuilder textBuilder = new StringBuilder();
                if (isAdmin) {
                    for (MatchPlayer matchPlayer : match.getMatchPlayers()) {
                        long sum = matchPlayer.getScore();
                        textBuilder.append("На ")
                                .append(matchPlayer.getPlayerName())
                                .append(" поставили: ")
                                .append(sum).append(" ⭐\n");
                    }
                    textBuilder.append("\n\n");
                }

                textBuilder.append("Матч уже начался. Перейти?");
                message.setText(textBuilder.toString());
                message.setReplyMarkup(keyboard);

                Integer messageId = bot.execute(message).getMessageId();
                createScoreMessage(match, chatId, messageId);
                return messageId;
            }
            case COMPLETED -> {
                InlineKeyboardButton button = createMatchLinkButton(match);

                InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                        .keyboardRow(List.of(button))
                        .build();

                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("Матч уже завершился. Перейти?");
                message.setReplyMarkup(keyboard);

                Integer messageId = bot.execute(message).getMessageId();
                createScoreMessage(match, chatId, messageId);
                return messageId;
            }
        }
        return null;
    }

    void createScoreMessage(Match match, Long chatId, Integer messageId) {
        ScoreMessage scoreMessage = new ScoreMessage();
        scoreMessage.setMessageId(Long.valueOf(messageId));
        scoreMessage.setMatch(match);
        scoreMessage.setChatId(chatId);
        scoreMessageRepository.save(scoreMessage);
    }

    private InlineKeyboardMarkup createActiveMatchToChannelForLineVotesKeyboard(Match match, boolean withBotLink) {
        var keyboard = new ArrayList<List<InlineKeyboardButton>>();
        var buttons = new ArrayList<InlineKeyboardButton>();
        keyboard.add(buttons);

        match.getMatchPlayers().forEach(matchPlayer -> {
            var button = new InlineKeyboardButton();
            button.setText(matchPlayer.getPlayerName());
            button.setCallbackData("vote_" + matchPlayer.getNumber() + "_" + match.getId());
            buttons.add(button);
        });

        if (withBotLink) {
            keyboard.add(List.of(createBotLinkButton()));
        }
        var markup = new InlineKeyboardMarkup();
        markup.setKeyboard(keyboard);
        return markup;
    }

    public void startLiveByActiveMatch(Long mainChannelChatId, PaymentBot bot) {
        var match = completeLineVotes(bot);
        if (match == null) {
            return;
        }

        Race race = new Race(match, raceProperties, getRandomTopPlayer(match), getRandomBottomPlayer(match));

        Integer messageId = printRace(mainChannelChatId, bot, race);
        match.setChannelTimerMessageId(messageId);
        match.setStatus(MatchStatus.LIVE);
        matchRepository.save(match);

        timerFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (race.isNotFinish()) {
                EditMessageText editMessage = new EditMessageText();
                editMessage.setChatId(mainChannelChatId);
                editMessage.setMessageId(messageId);
                editMessage.setText(race.paintRace());
                editMessage.setReplyMarkup(createBusterKeyboard(race.getMatch()));
                bot.execute(editMessage);
            } else {
                timerFuture.cancel(false);
                EditMessageText editMessage = new EditMessageText();
                editMessage.setChatId(mainChannelChatId);
                editMessage.setMessageId(messageId);
                editMessage.setText(race.publicFinishRace());
                bot.execute(editMessage);
                match.setStatus(MatchStatus.COMPLETED);
                winnerProcess(matchRepository.save(match), bot);
                log.info("Завершение матча. Расчёт результатов.");
            }
        }, Duration.of(2, ChronoUnit.SECONDS));
        raceService.startRace(race);
    }

    private void winnerProcess(Match match, PaymentBot bot) {
        Integer winnerNumber = match.getWinner();
        MatchPlayer winner = match.getPlayerByNumber(winnerNumber);

        paymentRequestRepository.completeLoseRequests(winner, winner.getMatch());
        jackpotService.update(match, bot);

        match.getMatchPlayers().forEach(matchPlayer ->
                playerRepository.findByName(matchPlayer.getPlayerName()).ifPresent(player -> {
                    player.setMatchCount(player.getMatchCount() + 1);
                    if (winner.getPlayerName().equals(player.getName())) {
                        player.setWinCount(player.getWinCount() + 1);
                    }
                    playerRepository.save(player);
                }));

        Map<Long, Long> result = paymentRequestRepository.findAllByMatchPlayerAndStatus(winner, PaymentRequestStatus.PAYED)
                .stream()
                .collect(Collectors.groupingBy(
                        PaymentRequest::getUserChatId,
                        Collectors.summingLong(PaymentRequest::getSum)
                ));

        result.forEach((userId, sum) -> {
            if (accountService.addBalance(userId, sum * 2)) {
                SendMessage message = new SendMessage(userId.toString(),
                        "Поздравляю с победой!\n\n" +
                                "Баланс успешно пополнен на " + sum + " ⭐.\n\n\uD83C\uDF40 " +
                                "Удача любит смелых! Скорее возвращайтесь в новых битвах!");
                message.setReplyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(List.of(createMatchLinkButton(match)))).build());
                bot.execute(message);

                List<PaymentRequest> completedRequests = paymentRequestRepository.findAllByMatchPlayerAndStatusAndUserChatId(
                        winner, PaymentRequestStatus.PAYED, userId).stream()
                        .peek(paymentRequest -> paymentRequest.setStatus(PaymentRequestStatus.COMPLETED))
                        .toList();
                paymentRequestRepository.saveAll(completedRequests);
            } else {
                SendMessage message = new SendMessage(userId.toString(),
                        "Поздравляю с победой!\n\n" +
                                "Произошла ошибка с пополнением баланса на " + sum + " ⭐.\n\n" +
                                "\uD83C\uDF40 Обратитесь за помощью по кнопке ниже. \uD83C\uDF40");
                message.setReplyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(List.of(createMatchLinkButton(match)))).build());
                bot.execute(message);

            }
        });
    }

    private Integer printRace(Long mainChannelChatId, PaymentBot bot, Race race) {
        SendMessage message = new SendMessage();
        message.setChatId(mainChannelChatId);
        message.setText(race.paintRace());
        message.setReplyMarkup(createBusterKeyboard(race.getMatch()));
        return bot.execute(message).getMessageId();
    }

    private InlineKeyboardMarkup createBusterKeyboard(Match match) {
        var keyboard = new ArrayList<List<InlineKeyboardButton>>();

        match.getMatchPlayers().forEach(matchPlayer ->
                keyboard.add(List.of(
                        createBusterButton(BusterType.BUST, matchPlayer, match),
                        createBusterButton(BusterType.SLOW, matchPlayer, match),
                        createBusterButton(BusterType.SHIELD, matchPlayer, match)
                )));
        keyboard.add(List.of(createBotLinkButton()));

        var markup = new InlineKeyboardMarkup();
        markup.setKeyboard(keyboard);
        return markup;
    }

    private InlineKeyboardButton createBotLinkButton() {
        var button3 = new InlineKeyboardButton();
        button3.setText("\uD83E\uDD16 Перейти в бота \uD83E\uDD16");
        button3.setUrl(botLink);
        return button3;
    }

    private InlineKeyboardButton createBusterButton(BusterType busterType, MatchPlayer matchPlayer, Match match) {
        var button = new InlineKeyboardButton();
        button.setText(busterType.getName() + " для " + matchPlayer.getPlayerName());
        button.setCallbackData(busterType.getQuery() + "_" + matchPlayer.getNumber() + "_" + match.getId());
        return button;
    }

    public Match completeLineVotes(PaymentBot bot) {
        var match = matchRepository.findLatestActiveMatchWithPlayers(List.of(MatchStatus.CREATED, MatchStatus.LIVE));
        if (match == null) {
            return null;
        }

        match.getMatchPlayers().forEach(matchPlayer -> {
            long sum = paymentRequestRepository.findAllByMatchPlayerAndStatus(
                            matchPlayer, PaymentRequestStatus.PAYED).stream()
                    .mapToLong(PaymentRequest::getSum)
                    .sum();
            matchPlayer.setScore(sum);
            matchPlayerRepository.save(matchPlayer);
        });

        String playerNames = match.getMatchPlayers().stream().map(MatchPlayer::getPlayerName).collect(Collectors.joining(" или "));

        scoreMessageRepository.findByMatch(match).forEach(scoreMessage -> {
            EditMessageText editMessage = new EditMessageText();
            editMessage.setChatId(scoreMessage.getChatId());
            editMessage.setMessageId(scoreMessage.getMessageId().intValue());
            editMessage.setText("❓❓❓   Кто победит?   ❓❓❓\n\n" +
                    playerNames + "\n\n" +
                    "Голосование завершено! Ожидайте старта гонки.\n");

            editMessage.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createMatchLinkButton(match)))));
            bot.execute(editMessage);
            bot.deleteMessageScheduled(scoreMessage.getChatId(), scoreMessage.getMessageId().intValue());
        });
        return match;
    }

    public InlineKeyboardButton createMatchLinkButton(Match match) {
        String messageLink = channelLink + match.getChannelTimerMessageId();
        return InlineKeyboardButton.builder()
                .text("📢 Перейти к матчу")
                .url(messageLink)
                .build();
    }

    public MatchPlayer getRandomTopPlayer(Match match) {
        return match.getRandomPlayerByBet(match, false);
    }

    public MatchPlayer getRandomBottomPlayer(Match match) {
        return match.getRandomPlayerByBet(match, true);
    }
}