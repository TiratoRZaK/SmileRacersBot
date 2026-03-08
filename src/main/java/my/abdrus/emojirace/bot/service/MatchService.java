package my.abdrus.emojirace.bot.service;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import my.abdrus.emojirace.bot.entity.Account;
import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.entity.Race;
import my.abdrus.emojirace.bot.entity.Match;
import my.abdrus.emojirace.bot.entity.MatchPlayer;
import my.abdrus.emojirace.bot.entity.PaymentRequest;
import my.abdrus.emojirace.bot.entity.Player;
import my.abdrus.emojirace.bot.entity.ScoreMessage;
import my.abdrus.emojirace.bot.enumeration.BusterType;
import my.abdrus.emojirace.bot.enumeration.MatchStatus;
import my.abdrus.emojirace.bot.enumeration.MatchType;
import my.abdrus.emojirace.bot.enumeration.PaymentRequestStatus;
import my.abdrus.emojirace.bot.exception.PaymentException;
import my.abdrus.emojirace.bot.repository.AccountRepository;
import my.abdrus.emojirace.bot.repository.MatchPlayerRepository;
import my.abdrus.emojirace.bot.repository.MatchRepository;
import my.abdrus.emojirace.bot.repository.PaymentRequestRepository;
import my.abdrus.emojirace.bot.repository.PlayerRepository;
import my.abdrus.emojirace.bot.repository.ScoreMessageRepository;
import my.abdrus.emojirace.config.ChannelProperties;
import my.abdrus.emojirace.config.RaceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import static my.abdrus.emojirace.bot.enumeration.MatchStatus.COMPLETED;
import static my.abdrus.emojirace.bot.enumeration.MatchStatus.CREATED;
import static my.abdrus.emojirace.bot.enumeration.MatchStatus.LIVE;

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
    private AccountRepository accountRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private RaceProperties raceProperties;
    @Autowired
    private ChannelProperties channelProperties;
    @Autowired
    private TaskScheduler scheduler;

    private ScheduledFuture<?> timerFuture;

    /**
     * Создать новый матч по именам игроков.
     */
    @Transactional
    public Match createMatchByPlayers(List<Player> players) {
        List<MatchPlayer> matchPlayers = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            matchPlayers.add(new MatchPlayer(players.get(i), i + 1));
        }

        Match match = Match.builder()
                .createdDate(new Date())
                .status(CREATED)
                .type(MatchType.REGULAR)
                .matchPlayers(matchPlayers)
                .build();

        matchPlayers.forEach(matchPlayer -> matchPlayer.setMatch(match));
        return matchRepository.save(match);
    }

    @Transactional
    public Match createBattle(Long creatorUserChatId, Player creatorPlayer, long stake) throws PaymentException {
        MatchPlayer creatorMatchPlayer = new MatchPlayer(creatorPlayer, 1);
        creatorMatchPlayer.setOwnerUserChatId(creatorUserChatId);

        Match match = Match.builder()
                .createdDate(new Date())
                .status(CREATED)
                .type(MatchType.BATTLE)
                .creatorUserChatId(creatorUserChatId)
                .matchPlayers(new ArrayList<>(List.of(creatorMatchPlayer)))
                .build();
        creatorMatchPlayer.setMatch(match);

        Match savedMatch = matchRepository.save(match);
        createBattleStake(savedMatch.getMatchPlayers().get(0), creatorUserChatId, stake);
        return savedMatch;
    }

    @Transactional
    public boolean joinBattle(Long matchId, Long userChatId, Player player, long stake) throws PaymentException {
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null || match.getType() != MatchType.BATTLE || match.getStatus() != CREATED) {
            return false;
        }
        boolean emojiExists = match.getMatchPlayers().stream().anyMatch(mp -> mp.getPlayerName().equals(player.getName()));
        boolean userExists = match.getMatchPlayers().stream().anyMatch(mp -> userChatId.equals(mp.getOwnerUserChatId()));
        if (emojiExists || userExists) {
            return false;
        }

        MatchPlayer matchPlayer = new MatchPlayer(player, match.getMatchPlayers().size() + 1);
        matchPlayer.setOwnerUserChatId(userChatId);
        matchPlayer.setMatch(match);
        match.getMatchPlayers().add(matchPlayer);
        matchPlayerRepository.save(matchPlayer);
        createBattleStake(matchPlayer, userChatId, stake);
        return true;
    }

    @Transactional
    public Match cancelBattle(Long matchId, Long initiatorUserChatId) {
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null || match.getType() != MatchType.BATTLE || match.getStatus() != CREATED
                || !initiatorUserChatId.equals(match.getCreatorUserChatId())) {
            return null;
        }
        match.setStatus(COMPLETED);

        match.getMatchPlayers().forEach(matchPlayer ->
                paymentRequestRepository.findAllByMatchPlayerAndStatus(matchPlayer, PaymentRequestStatus.PAYED)
                        .forEach(request -> {
                            accountService.addBalance(request.getUserChatId(), request.getSum());
                            request.setStatus(PaymentRequestStatus.COMPLETED);
                            paymentRequestRepository.save(request);
                        }));

        return matchRepository.save(match);
    }

    public boolean canStartBattle(Long matchId, Long initiatorUserChatId) {
        Match match = matchRepository.findById(matchId).orElse(null);
        return match != null
                && match.getType() == MatchType.BATTLE
                && match.getStatus() == CREATED
                && initiatorUserChatId.equals(match.getCreatorUserChatId())
                && match.getMatchPlayers().size() > 1;
    }

    public List<Player> getAvailableBattlePlayers(Long matchId) {
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null) {
            return List.of();
        }
        return playerRepository.findAllByNameNotIn(match.getMatchPlayers().stream().map(MatchPlayer::getPlayerName).toList());
    }

    public long getBattleStake(Match match) {
        return match.getMatchPlayers().stream()
                .findFirst()
                .flatMap(matchPlayer -> paymentRequestRepository.findAllByMatchPlayerAndStatus(matchPlayer, PaymentRequestStatus.PAYED)
                        .stream().findFirst())
                .map(PaymentRequest::getSum)
                .orElse(0L);
    }

    private void createBattleStake(MatchPlayer matchPlayer, Long userChatId, long stake) throws PaymentException {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setMatchPlayer(matchPlayer);
        paymentRequest.setUserChatId(userChatId);
        paymentRequest.setSum(stake);
        paymentRequest.setStatus(PaymentRequestStatus.WAIT_PAYMENT);
        paymentRequest.setCreatedDate(new Date());
        PaymentRequest saved = paymentRequestRepository.save(paymentRequest);
        accountService.pay(saved);
    }

    public Integer sendActiveMatchStateToChannel(Long chatId,
                                                 boolean isMainChannel,
                                                 EmojiRaceBot bot) {
        return matchRepository
                .findFirstByStatusInOrderByCreatedDateDesc(Arrays.asList(MatchStatus.values()))
                .map(Match::getId)
                .flatMap(id -> matchRepository.findById(id))
                .map(match -> {
                    return switch (match.getStatus()) {
                        case CREATED -> {
                            String playerNames = match.getMatchPlayers().stream()
                                    .map(MatchPlayer::getPlayerName)
                                    .collect(Collectors.joining(" или "));

                            var message = new SendMessage();

                            message.setChatId(chatId.toString());
                            String text = "Гонка №" + match.getId() + "\n\n" +
                                    "❓❓❓   Кто победит?   ❓❓❓\n\n" +
                                    playerNames +
                                    "\n\n" +
                                    "Решайся и голосуй звёздами!\n\n" +
                                    "Пополнить баланс звёзд можно перейдя в бота.";
                            message.setText(text);

                            var markup = createActiveMatchToChannelForLineVotesKeyboard(match, isMainChannel);
                            message.setReplyMarkup(markup);

                            Integer messageId = bot.execute(message).getMessageId();
                            createScoreMessage(match, chatId, messageId);
                            yield messageId;
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
                            yield messageId;
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
                            yield messageId;
                        }
                    };
                })
                .orElse(null);
    }

    public void sendMatchLineToChannel(Match match, EmojiRaceBot bot) {
        if (match == null) {
            return;
        }

        Long mainChannelChatId = channelProperties.getMainChannelChatId();

        String playerNames = match.getMatchPlayers().stream()
                .map(MatchPlayer::getPlayerName)
                .collect(Collectors.joining(" или "));

        var message = new SendMessage();

        message.setChatId(mainChannelChatId.toString());
        String text = "Гонка №" + match.getId() + "\n\n" +
                "❓❓❓   Кто победит?   ❓❓❓\n\n" +
                playerNames +
                "\n\n" +
                "Решайся и голосуй звёздами!\n\n" +
                "Пополнить баланс звёзд можно перейдя в бота.";
        message.setText(text);

        var markup = createActiveMatchToChannelForLineVotesKeyboard(match, true);
        message.setReplyMarkup(markup);

        log.info("Старт линии гонки #{}", match.getId());
        Integer messageId = bot.execute(message).getMessageId();
        createScoreMessage(match, mainChannelChatId, messageId);
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
        } else {
            keyboard.add(List.of(createMatchLinkButton(match)));
        }
        var markup = new InlineKeyboardMarkup();
        markup.setKeyboard(keyboard);
        return markup;
    }

    public void startLiveByActiveMatch(Match match, EmojiRaceBot bot) {
        match = completeLineVotes(match, bot);
        if (match == null) {
            return;
        }

        Long mainChannelChatId = channelProperties.getMainChannelChatId();

        Race race = new Race(match, raceProperties, getTopPayedPlayerOrRandom(match), getBottomPayedPlayerRandom(match));

        Integer messageId = sendRaceStateMessage(mainChannelChatId, bot, race);
        match.setChannelTimerMessageId(messageId);
        match.setStatus(LIVE);
        Match savedMatch = matchRepository.save(match);
        race.setMatch(savedMatch);

        log.info("Старт лайва гонки #{}.", match.getId());
        timerFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (race.isNotFinish()) {
                EditMessageText editMessage = new EditMessageText();
                editMessage.setChatId(mainChannelChatId);
                editMessage.setMessageId(messageId);
                editMessage.setText(race.getRaceStateMessage());
                editMessage.setReplyMarkup(createBusterKeyboard(race.getMatch()));
                bot.execute(editMessage);
            } else {
                timerFuture.cancel(false);
                EditMessageText editMessage = new EditMessageText();
                editMessage.setChatId(mainChannelChatId);
                editMessage.setMessageId(messageId);
                editMessage.setText(race.getCompletedRaceStateMessage());
                editMessage.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createBotLinkButton()))));
                bot.execute(editMessage);
                Match raceMatch = race.getMatch();
                raceMatch.setStatus(COMPLETED);
                raceMatch = matchRepository.save(raceMatch);
                race.setMatch(raceMatch);
                winnerProcess(matchRepository.save(raceMatch), bot);
                log.info("Завершение гонки #{}. Расчёт результатов.", savedMatch.getId());
            }
        }, Duration.of(1, ChronoUnit.SECONDS));
        raceService.startRace(race);
    }

    private void winnerProcess(Match match, EmojiRaceBot bot) {
        if (match.getType() == MatchType.BATTLE) {
            battleWinnerProcess(match, bot);
            return;
        }

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
            boolean addFreeBust = false;
            Player favoritePlayer = userService.createIfNeed(userId).getFavoritePlayer();
            if (favoritePlayer != null && favoritePlayer.equals(winner.getPlayer())) {
                Account account = accountService.getByUserId(userId);
                account.setFreeBustCount(account.getFreeBustCount() + 1);
                accountRepository.save(account);
                addFreeBust = true;
            }

            if (accountService.addBalance(userId, sum * 2)) {
                String text = "Поздравляю с победой!\n\n" +
                        "Баланс успешно пополнен на " + sum * 2 + " ⭐.\n\n\uD83C\uDF40 " +
                        "Удача любит смелых! Скорее возвращайтесь в новых битвах!";
                if (addFreeBust) {
                    text += "\n\n\nВаш любимый смайл " + favoritePlayer.getName() + " победил! Вам начислен один бесплатный бустер!\n" +
                            "Можете использовать его в любой следующей битве!";
                }
                SendMessage message = new SendMessage(userId.toString(), text);
                message.setReplyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(List.of(createMatchLinkButton(match)))).build());
                bot.execute(message);

                List<PaymentRequest> completedRequests = paymentRequestRepository.findAllByMatchPlayerAndStatusAndUserChatId(
                        winner, PaymentRequestStatus.PAYED, userId).stream()
                        .peek(paymentRequest -> {
                            paymentRequest.setStatus(PaymentRequestStatus.COMPLETED);
                            paymentRequest.setToWinner(true);
                        })
                        .toList();
                paymentRequestRepository.saveAll(completedRequests);
            } else {
                String text = "Поздравляю с победой!\n\n" +
                        "Произошла ошибка с пополнением баланса на " + sum * 2 + " ⭐.\n\n" +
                        "\uD83C\uDF40 Обратитесь за помощью по кнопке ниже. \uD83C\uDF40";
                if (addFreeBust) {
                    text += "\n\n\nВаш любимый смайл " + favoritePlayer.getName() + " победил! Вам начислен один бесплатный бустер!\n" +
                            "Можете использовать его в любой следующей битве!";
                }
                SendMessage message = new SendMessage(userId.toString(), text);
                message.setReplyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(List.of(createMatchLinkButton(match)))).build());
                bot.execute(message);

            }
        });
    }

    private void battleWinnerProcess(Match match, EmojiRaceBot bot) {
        Integer winnerNumber = match.getWinner();
        MatchPlayer winner = match.getPlayerByNumber(winnerNumber);
        if (winner == null || winner.getOwnerUserChatId() == null) {
            return;
        }

        paymentRequestRepository.completeLoseRequests(winner, winner.getMatch());

        long battleBank = match.getMatchPlayers().stream()
                .mapToLong(matchPlayer -> matchPlayer.getScore() == null ? 0L : matchPlayer.getScore())
                .sum();
        long winnerAmount = Math.round(battleBank * 0.95d);

        Long winnerUserChatId = winner.getOwnerUserChatId();
        if (accountService.addBalance(winnerUserChatId, winnerAmount)) {
            SendMessage winnerMessage = new SendMessage(
                    winnerUserChatId.toString(),
                    "🏆 Вы победили в батле #" + match.getId() + "!\n\n" +
                            "На ваш баланс начислено " + winnerAmount + " ⭐ (95% от банка " + battleBank + " ⭐)."
            );
            winnerMessage.setReplyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(List.of(createMatchLinkButton(match)))).build());
            bot.execute(winnerMessage);
        }

        paymentRequestRepository.findAllByMatchPlayerAndStatus(winner, PaymentRequestStatus.PAYED)
                .forEach(paymentRequest -> {
                    paymentRequest.setStatus(PaymentRequestStatus.COMPLETED);
                    paymentRequest.setToWinner(true);
                    paymentRequestRepository.save(paymentRequest);
                });

        match.getMatchPlayers().stream()
                .filter(matchPlayer -> !matchPlayer.getNumber().equals(winnerNumber))
                .map(MatchPlayer::getOwnerUserChatId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(loserUserChatId -> {
                    SendMessage loseMessage = new SendMessage(
                            loserUserChatId.toString(),
                            "😔 Батл #" + match.getId() + " завершён. К сожалению, в этот раз вы проиграли."
                    );
                    loseMessage.setReplyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(List.of(createMatchLinkButton(match)))).build());
                    bot.execute(loseMessage);
                });
    }

    private Integer sendRaceStateMessage(Long mainChannelChatId, EmojiRaceBot bot, Race race) {
        SendMessage message = new SendMessage();
        message.setChatId(mainChannelChatId);
        message.setText(race.getRaceStateMessage());
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
        button3.setUrl(channelProperties.getBotLink());
        return button3;
    }

    private InlineKeyboardButton createBusterButton(BusterType busterType, MatchPlayer matchPlayer, Match match) {
        var button = new InlineKeyboardButton();
        button.setText(busterType.getName() + " для " + matchPlayer.getPlayerName());
        button.setCallbackData(busterType.getQuery() + "_" + matchPlayer.getNumber() + "_" + match.getId());
        return button;
    }

    public Match completeLineVotes(Match match, EmojiRaceBot bot) {
        if (match == null) {
            return null;
        }

        log.info("Завершение голосования гонки #{}. Фиксация голосов.", match.getId());
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
        String messageLink = channelProperties.getChannelLink() + match.getChannelTimerMessageId();
        return InlineKeyboardButton.builder()
                .text("📢 Перейти к матчу")
                .url(messageLink)
//                .webApp(new WebAppInfo("https://smile-racers-ui.vercel.app/"))
                .build();
    }

    public MatchPlayer getTopPayedPlayerOrRandom(Match match) {
        return match.getRandomPlayerByBet(match, false);
    }

    public MatchPlayer getBottomPayedPlayerRandom(Match match) {
        return match.getRandomPlayerByBet(match, true);
    }
}
