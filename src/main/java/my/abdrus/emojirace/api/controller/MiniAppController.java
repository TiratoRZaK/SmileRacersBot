package my.abdrus.emojirace.api.controller;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.abdrus.emojirace.api.dto.MiniAppDtos;
import my.abdrus.emojirace.bot.entity.Account;
import my.abdrus.emojirace.bot.entity.Match;
import my.abdrus.emojirace.bot.entity.PaymentRequest;
import my.abdrus.emojirace.bot.entity.MatchPlayer;
import my.abdrus.emojirace.bot.entity.Player;
import my.abdrus.emojirace.bot.entity.UserNotification;
import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.entity.Race;
import my.abdrus.emojirace.bot.enumeration.BusterType;
import my.abdrus.emojirace.bot.enumeration.MatchStatus;
import my.abdrus.emojirace.bot.enumeration.MatchType;
import my.abdrus.emojirace.bot.enumeration.PaymentRequestStatus;
import my.abdrus.emojirace.bot.enumeration.WithdrawRequestStatus;
import my.abdrus.emojirace.bot.exception.PaymentException;
import my.abdrus.emojirace.bot.repository.AccountRepository;
import my.abdrus.emojirace.bot.repository.MatchRepository;
import my.abdrus.emojirace.bot.repository.PaymentRequestRepository;
import my.abdrus.emojirace.bot.repository.PlayerRepository;
import my.abdrus.emojirace.bot.repository.UserRepository;
import my.abdrus.emojirace.bot.service.AccountService;
import my.abdrus.emojirace.bot.service.InvoiceService;
import my.abdrus.emojirace.bot.service.MatchGenerationService;
import my.abdrus.emojirace.bot.service.MatchService;
import my.abdrus.emojirace.bot.service.RaceService;
import my.abdrus.emojirace.bot.service.UserNotificationService;
import my.abdrus.emojirace.bot.service.UserHistoryReportService;
import my.abdrus.emojirace.bot.service.UserService;
import my.abdrus.emojirace.bot.service.WithdrawService;
import my.abdrus.emojirace.config.BotProperties;
import my.abdrus.emojirace.config.RaceProperties;
import org.json.JSONObject;
import my.abdrus.emojirace.config.ChannelProperties;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/miniapp")
@RequiredArgsConstructor
@Slf4j
public class MiniAppController {

    private static final long FAVORITE_REPLACE_COST = 150L;
    private static final Pattern USER_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final long WEB_AUTH_TTL_SECONDS = 60L * 60L * 24L * 7L;

    private final EmojiRaceBot bot;
    private final AccountService accountService;
    private final AccountRepository accountRepository;
    private final UserService userService;
    private final UserRepository userRepository;
    private final MatchRepository matchRepository;
    private final RaceService raceService;
    private final PlayerRepository playerRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final MatchGenerationService matchGenerationService;
    private final MatchService matchService;
    private final WithdrawService withdrawService;
    private final InvoiceService invoiceService;
    private final UserNotificationService userNotificationService;
    private final UserHistoryReportService userHistoryReportService;
    private final ChannelProperties channelProperties;
    private final BotProperties botProperties;
    private final RaceProperties raceProperties;

    @GetMapping("/bootstrap")
    public MiniAppDtos.BootstrapResponse bootstrap(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        Account account = accountService.getByUserId(userId);
        var user = userService.createIfNeed(userId);

        Race activeRace = raceService.getActiveRace();
        Match selectedMatch = activeRace != null
                ? activeRace.getMatch()
                : matchRepository
                .findTop10ByStatusInOrderByCreatedDateDesc(List.of(MatchStatus.CREATED, MatchStatus.LIVE)).stream()
                .filter(match -> match.getType() != MatchType.BATTLE || match.getStatus() == MatchStatus.LIVE)
                .findFirst()
                .orElse(null);

        MiniAppDtos.RaceCard raceCard = toRaceCard(selectedMatch, activeRace, userId);
        MiniAppDtos.RaceCard myBattleCard = matchRepository
                .findFirstByTypeAndStatusInAndMatchPlayers_OwnerUserChatIdOrderByCreatedDateDesc(
                        MatchType.BATTLE,
                        List.of(MatchStatus.CREATED, MatchStatus.LIVE),
                        userId
                )
                .map(match -> toRaceCard(match, activeRace, userId))
                .orElse(null);

        List<String> emojis = playerRepository.findAll().stream()
                .map(Player::getName)
                .sorted(Comparator.naturalOrder())
                .toList();

        List<MiniAppDtos.UiNotification> notifications = userNotificationService.getRecent(userId).stream()
                .map(item -> new MiniAppDtos.UiNotification(item.getId(), item.getText(), item.getCreatedDate().getTime()))
                .toList();

        return new MiniAppDtos.BootstrapResponse(
                userId,
                isLocalTestModeActive(),
                Optional.ofNullable(raceProperties.getGenerationIntervalMinutes()).orElse(3),
                account.getBalance(),
                account.getFreeBustCount(),
                user.getFavoritePlayer() == null ? null : user.getFavoritePlayer().getName(),
                raceCard,
                myBattleCard,
                emojis,
                notifications
        );
    }

    @GetMapping("/auth/config")
    public MiniAppDtos.TelegramAuthConfigResponse telegramAuthConfig() {
        return new MiniAppDtos.TelegramAuthConfigResponse(botProperties.getUsername());
    }

    @PostMapping("/auth/telegram")
    public MiniAppDtos.TelegramWebAuthResponse telegramWebAuth(@RequestBody Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return new MiniAppDtos.TelegramWebAuthResponse(false, "Пустые данные Telegram авторизации.", null, null, null);
        }

        if (!isValidTelegramLoginPayload(payload)) {
            return new MiniAppDtos.TelegramWebAuthResponse(false, "Не удалось проверить подпись Telegram.", null, null, null);
        }

        Long userId = parseLong(payload.get("id"));
        if (userId == null || userId < 1) {
            return new MiniAppDtos.TelegramWebAuthResponse(false, "Не удалось определить Telegram user id.", null, null, null);
        }

        accountService.getByUserId(userId);
        userService.createIfNeed(userId);
        String accountLabel = extractTelegramAccountLabel(payload, userId);
        String authToken = generateWebAuthToken(userId);
        return new MiniAppDtos.TelegramWebAuthResponse(true, "Авторизация выполнена.", userId, authToken, accountLabel);
    }

    @PostMapping("/vote")
    public MiniAppDtos.ActionResponse vote(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.VoteRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.matchId() == null || request.playerNumber() == null || request.amount() == null || request.amount() < 1) {
            return new MiniAppDtos.ActionResponse(false, "Некорректные параметры голосования.");
        }
        Match match = matchRepository.findById(request.matchId()).orElse(null);
        if (match == null) {
            return new MiniAppDtos.ActionResponse(false, "Гонка не найдена.");
        }
        if (!MatchStatus.CREATED.equals(match.getStatus())) {
            return new MiniAppDtos.ActionResponse(false, "Голосование закрыто для этой гонки.");
        }
        var matchPlayer = match.getPlayerByNumber(request.playerNumber());
        if (matchPlayer == null) {
            return new MiniAppDtos.ActionResponse(false, "Смайл не найден в этой гонке.");
        }

        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setMatchPlayer(matchPlayer);
        paymentRequest.setStatus(PaymentRequestStatus.WAIT_PAYMENT);
        paymentRequest.setSum(request.amount());
        paymentRequest.setUserChatId(userId);
        paymentRequest.setCreatedDate(new Date());
        PaymentRequest saved = paymentRequestRepository.save(paymentRequest);

        try {
            accountService.pay(saved);
            saved.setStatus(PaymentRequestStatus.PAYED);
            saved.setPayedDate(new Date());
            paymentRequestRepository.save(saved);
            return new MiniAppDtos.ActionResponse(true, "Голос принят.");
        } catch (PaymentException e) {
            return new MiniAppDtos.ActionResponse(false, e.getMessage());
        }
    }

    @PostMapping("/boost")
    public MiniAppDtos.ActionResponse boost(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.BoostRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.playerNumber() == null || request.type() == null) {
            return new MiniAppDtos.ActionResponse(false, "Некорректные параметры бустера.");
        }
        BusterType type;
        try {
            type = BusterType.valueOf(request.type().toUpperCase());
        } catch (Exception e) {
            return new MiniAppDtos.ActionResponse(false, "Неизвестный тип бустера.");
        }

        try {
            Account account = accountService.getByUserId(userId);
            if (account.getFreeBustCount() > 0) {
                account.setFreeBustCount(account.getFreeBustCount() - 1);
                accountRepository.save(account);
            } else {
                PaymentRequest paymentRequest = new PaymentRequest();
                paymentRequest.setUserChatId(userId);
                paymentRequest.setSum(type.getCost());
                accountService.pay(paymentRequest);
            }
            raceService.addTickForPlayer(request.playerNumber(), type);
            return new MiniAppDtos.ActionResponse(true, "Бустер активирован: " + type.getName());
        } catch (PaymentException e) {
            return new MiniAppDtos.ActionResponse(false, e.getMessage());
        }
    }

    @PostMapping("/favorite")
    public MiniAppDtos.ActionResponse setFavorite(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.FavoriteRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.playerName() == null) {
            return new MiniAppDtos.ActionResponse(false, "Выберите смайл.");
        }
        Player player = playerRepository.findByName(request.playerName()).orElse(null);
        if (player == null) {
            return new MiniAppDtos.ActionResponse(false, "Смайл не найден.");
        }
        var user = userService.createIfNeed(userId);
        if (user.getFavoritePlayer() != null && !user.getFavoritePlayer().getName().equals(player.getName())) {
            PaymentRequest paymentRequest = new PaymentRequest();
            paymentRequest.setUserChatId(userId);
            paymentRequest.setSum(FAVORITE_REPLACE_COST);
            try {
                accountService.pay(paymentRequest);
            } catch (PaymentException e) {
                return new MiniAppDtos.ActionResponse(false, e.getMessage());
            }
        }

        user.setFavoritePlayer(player);
        userRepository.save(user);
        return new MiniAppDtos.ActionResponse(true, "Любимый смайл обновлён.");
    }

    @PostMapping("/queue")
    public MiniAppDtos.ActionResponse queueFavorite(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.QueueRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        var user = userService.createIfNeed(userId);
        if (user.getFavoritePlayer() == null) {
            return new MiniAppDtos.ActionResponse(false, "Сначала выберите любимый смайл.");
        }

        if (request != null && request.playerName() != null
                && !request.playerName().equals(user.getFavoritePlayer().getName())) {
            return new MiniAppDtos.ActionResponse(false, "В очередь можно отправить только любимый смайл.");
        }

        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setUserChatId(userId);
        paymentRequest.setSum(10L);
        try {
            accountService.pay(paymentRequest);
        } catch (PaymentException e) {
            return new MiniAppDtos.ActionResponse(false, e.getMessage());
        }
        int position = matchGenerationService.addPlayerToQueue(user.getFavoritePlayer());
        return new MiniAppDtos.ActionResponse(true, "Смайл добавлен в очередь. В очереди перед тобой: " + position);
    }

    @PostMapping("/topup")
    public MiniAppDtos.TopupLinkResponse topup(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.TopupRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.amount() == null || request.amount() < 1) {
            return new MiniAppDtos.TopupLinkResponse(false, "Сумма должна быть больше 0.", null);
        }
        String invoiceLink = invoiceService.createDepositInvoiceLink(userId, request.amount());
        if (invoiceLink == null) {
            return new MiniAppDtos.TopupLinkResponse(false, "Не удалось сформировать ссылку на оплату.", null);
        }
        return new MiniAppDtos.TopupLinkResponse(true, "Ссылка на пополнение сформирована.", invoiceLink);
    }

    @PostMapping("/withdraw")
    public MiniAppDtos.ActionResponse withdraw(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.WithdrawRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.amount() == null || request.amount() < 100) {
            return new MiniAppDtos.ActionResponse(false, "Сумма вывода должна быть не меньше 100 ⭐.");
        }
        if (accountService.getBalanceByUserChatId(userId).compareTo(request.amount()) < 0) {
            return new MiniAppDtos.ActionResponse(false, "Недостаточно средств на балансе.");
        }
        try {
            Long id = withdrawService.sendWithdrawRequestToAdmin(userId, request.amount(), new Date());
            return new MiniAppDtos.ActionResponse(true, "Запрос на вывод создан: #" + id);
        } catch (Exception e) {
            return new MiniAppDtos.ActionResponse(false, e.getMessage());
        }
    }

    @GetMapping("/withdraw/active")
    public MiniAppDtos.ActiveWithdrawsResponse activeWithdraws(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        var items = withdrawService.findByUser(userId).stream()
                .filter(request -> WithdrawRequestStatus.CREATED.equals(request.getStatus()))
                .map(request -> new MiniAppDtos.WithdrawItem(
                        request.getId(),
                        request.getSum(),
                        request.getStatus().name(),
                        request.getCreatedDate() == null ? null : request.getCreatedDate().getTime()
                ))
                .toList();
        return new MiniAppDtos.ActiveWithdrawsResponse(items);
    }

    @PostMapping("/withdraw/cancel")
    public MiniAppDtos.ActionResponse cancelWithdraw(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.CancelWithdrawRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.requestId() == null) {
            return new MiniAppDtos.ActionResponse(false, "Некорректный запрос на отмену вывода.");
        }
        boolean canceled = withdrawService.cancelByUserForMiniApp(userId, request.requestId());
        if (!canceled) {
            return new MiniAppDtos.ActionResponse(false, "Не удалось отменить вывод.");
        }
        return new MiniAppDtos.ActionResponse(true, "Запрос на вывод отменён.");
    }

    @PostMapping("/battle")
    public MiniAppDtos.ActionResponse createBattle(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.CreateBattleRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.playerName() == null || request.stake() == null || request.stake() < 1) {
            return new MiniAppDtos.ActionResponse(false, "Некорректные параметры батла.");
        }
        Player player = playerRepository.findByName(request.playerName()).orElse(null);
        if (player == null) {
            return new MiniAppDtos.ActionResponse(false, "Смайл не найден.");
        }
        try {
            Match battle = matchService.createBattle(userId, player, request.stake());
            if (battle == null) {
                return new MiniAppDtos.ActionResponse(false, "У вас уже есть открытый батл. Завершите его или отмените.");
            }
            return new MiniAppDtos.ActionResponse(true, "Батл создан: #" + battle.getId());
        } catch (PaymentException e) {
            return new MiniAppDtos.ActionResponse(false, e.getMessage());
        }
    }

    @PostMapping("/battle/start")
    public MiniAppDtos.ActionResponse startBattle(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.StartBattleRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.matchId() == null) {
            return new MiniAppDtos.ActionResponse(false, "Некорректный запрос на старт батла.");
        }
        if (!matchService.canStartBattle(request.matchId(), userId)) {
            return new MiniAppDtos.ActionResponse(false, "Старт недоступен: нужен создатель и минимум 2 участника.");
        }

        if (matchService.requestBattleStart(request.matchId(), userId)) {
            return new MiniAppDtos.ActionResponse(true, "Батл поставлен в очередь и стартует автоматически, когда придёт его очередь.");
        }
        return new MiniAppDtos.ActionResponse(false, "Не удалось поставить батл в очередь.");
    }

    @PostMapping("/battle/remove-participant")
    public MiniAppDtos.ActionResponse removeBattleParticipant(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.RemoveBattleParticipantRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.matchId() == null || request.playerNumber() == null) {
            return new MiniAppDtos.ActionResponse(false, "Некорректный запрос на исключение участника.");
        }

        boolean removed = matchService.removeBattleParticipant(request.matchId(), userId, request.playerNumber());
        if (!removed) {
            return new MiniAppDtos.ActionResponse(false, "Не удалось исключить участника из батла.");
        }
        return new MiniAppDtos.ActionResponse(true, "Участник исключён, его ставка возвращена на баланс.");
    }

    @PostMapping("/battle/join")
    public MiniAppDtos.ActionResponse joinBattle(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.JoinBattleRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.matchId() == null || request.playerName() == null || request.playerName().isBlank()) {
            return new MiniAppDtos.ActionResponse(false, "Некорректный запрос на участие в батле.");
        }

        Match battle = matchRepository.findById(request.matchId()).orElse(null);
        if (battle == null || battle.getType() != MatchType.BATTLE || battle.getStatus() != MatchStatus.CREATED) {
            return new MiniAppDtos.ActionResponse(false, "Батл недоступен.");
        }

        Player player = playerRepository.findByName(request.playerName()).orElse(null);
        if (player == null) {
            return new MiniAppDtos.ActionResponse(false, "Смайл не найден.");
        }

        try {
            boolean joined = matchService.joinBattle(request.matchId(), userId, player, matchService.getBattleStake(battle));
            if (!joined) {
                return new MiniAppDtos.ActionResponse(false, "Нельзя присоединиться (смайл/участник уже есть или батл закрыт).");
            }
            if (battle.getCreatorUserChatId() != null) {
                matchService.refreshBattleCreatorMessage(request.matchId(), bot);
            }
            return new MiniAppDtos.ActionResponse(true, "Вы присоединились к батлу #" + request.matchId() + " за " + matchService.getBattleStake(battle) + " ⭐.");
        } catch (PaymentException e) {
            return new MiniAppDtos.ActionResponse(false, e.getMessage());
        }
    }

    @PostMapping("/battle/leave")
    public MiniAppDtos.ActionResponse leaveBattle(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.CancelBattleRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.matchId() == null) {
            return new MiniAppDtos.ActionResponse(false, "Некорректный запрос на выход из батла.");
        }

        boolean left = matchService.leaveBattle(request.matchId(), userId);
        if (!left) {
            return new MiniAppDtos.ActionResponse(false, "Выйти из батла можно только до старта.");
        }
        return new MiniAppDtos.ActionResponse(true, "Вы вышли из батла. Ставка возвращена на баланс.");
    }

    @PostMapping("/battle/cancel")
    public MiniAppDtos.ActionResponse cancelBattle(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.CancelBattleRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.matchId() == null) {
            return new MiniAppDtos.ActionResponse(false, "Некорректный запрос на отмену батла.");
        }

        Match canceled = matchService.cancelBattle(request.matchId(), userId);
        if (canceled == null) {
            return new MiniAppDtos.ActionResponse(false, "Отмена недоступна.");
        }
        return new MiniAppDtos.ActionResponse(true, "Батл отменён.");
    }

    @GetMapping("/recent-results")
    public MiniAppDtos.RecentResultsResponse recentResults(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam
    ) {
        resolveUserId(headerUserId, userIdParam);
        List<MiniAppDtos.RaceResultCard> items = matchRepository
                .findTop5ByStatusOrderByCreatedDateDesc(MatchStatus.COMPLETED).stream()
                .map(this::toRaceResultCard)
                .toList();
        return new MiniAppDtos.RecentResultsResponse(items);
    }

    @GetMapping("/history")
    public MiniAppDtos.HistoryResponse history(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        List<MiniAppDtos.HistoryItem> items = userHistoryReportService.loadHistoryForMiniApp(userId).stream()
                .map(item -> new MiniAppDtos.HistoryItem(
                        item.createdDate() == null ? null : item.createdDate().getTime(),
                        item.operation(),
                        item.amount(),
                        item.details()
                ))
                .toList();
        return new MiniAppDtos.HistoryResponse(items);
    }


    @PostMapping("/history/export")
    public MiniAppDtos.ActionResponse exportHistory(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        userHistoryReportService.sendHistoryFile(userId, userId, bot);
        return new MiniAppDtos.ActionResponse(true, "Файл истории отправлен в чат с ботом.");
    }

    @PostMapping("/notification/delete")
    public MiniAppDtos.ActionResponse deleteNotification(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam,
            @RequestBody MiniAppDtos.DeleteNotificationRequest request
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        if (request == null || request.notificationId() == null) {
            return new MiniAppDtos.ActionResponse(false, "Не выбрано уведомление для удаления.");
        }

        UserNotification notification = userNotificationService.findByIdAndUserId(request.notificationId(), userId).orElse(null);
        if (notification == null) {
            return new MiniAppDtos.ActionResponse(false, "Уведомление не найдено.");
        }

        if (notification.getMessageId() != null) {
            deleteTelegramMessageIfExists(userId, notification.getMessageId());
        } else {
            userNotificationService.delete(notification);
        }
        return new MiniAppDtos.ActionResponse(true, "Уведомление удалено.");
    }

    @PostMapping("/notification/clear")
    public MiniAppDtos.ActionResponse clearNotifications(
            @RequestHeader(value = "X-Telegram-User-Id", required = false) Long headerUserId,
            @RequestParam(value = "userId", required = false) Long userIdParam
    ) {
        Long userId = resolveUserId(headerUserId, userIdParam);
        List<UserNotification> notifications = userNotificationService.getAllByUserId(userId);
        List<UserNotification> withoutTelegramMessage = notifications.stream()
                .filter(notification -> notification.getMessageId() == null)
                .toList();
        notifications.stream()
                .filter(notification -> notification.getMessageId() != null)
                .forEach(notification -> deleteTelegramMessageIfExists(userId, notification.getMessageId()));
        userNotificationService.deleteAll(withoutTelegramMessage);
        return new MiniAppDtos.ActionResponse(true, "Уведомления очищены.");
    }

    @GetMapping("/help")
    public MiniAppDtos.ActionResponse help() {
        return new MiniAppDtos.ActionResponse(true, channelProperties.getHelpLink());
    }

    private MiniAppDtos.RaceCard toRaceCard(Match match, Race activeRace, Long userId) {
        if (match == null) {
            return null;
        }
        List<MiniAppDtos.RaceUnit> units = Optional.ofNullable(match.getMatchPlayers()).orElse(List.of()).stream()
                .sorted(Comparator.comparingInt(mp -> mp.getNumber() == null ? 0 : mp.getNumber()))
                .map(mp -> new MiniAppDtos.RaceUnit(
                        mp.getNumber(),
                        mp.getPlayerName(),
                        mp.getOwnerUserChatId() == null ? null : userService.getUsernameOrFallback(mp.getOwnerUserChatId()),
                        mp.getOwnerUserChatId(),
                        activeRace != null && activeRace.getMatch().getId().equals(match.getId())
                                ? Math.round(activeRace.getScoreByNumber(mp.getNumber()))
                                : 0L,
                        paymentRequestRepository.sumMyVotesByMatchPlayer(mp, userId),
                        activeRace != null && activeRace.getMatch().getId().equals(match.getId())
                                ? activeRace.getShieldsByNumber(mp.getNumber())
                                : 0,
                        activeRace != null && activeRace.getMatch().getId().equals(match.getId())
                                ? activeRace.getLastAppliedBusterByNumber(mp.getNumber()).name()
                                : null
                ))
                .toList();

        Long trackLength = activeRace != null && activeRace.getMatch().getId().equals(match.getId())
                ? Math.round(activeRace.getRaceSize())
                : null;

        String inviteLink = match.getType() == MatchType.BATTLE
                ? channelProperties.getBotLink() + "?start=join_battle_" + match.getId()
                : null;

        return new MiniAppDtos.RaceCard(
                match.getId(),
                match.getStatus().name(),
                match.getType().name(),
                trackLength,
                match.getBattleStake(),
                match.isBattleStartRequested(),
                inviteLink,
                units
        );
    }


    private MiniAppDtos.RaceResultCard toRaceResultCard(Match match) {
        if (match == null) {
            return null;
        }

        List<MiniAppDtos.RaceUnit> units = Optional.ofNullable(match.getMatchPlayers()).orElse(List.of()).stream()
                .sorted(Comparator.comparingInt(mp -> mp.getNumber() == null ? 0 : mp.getNumber()))
                .map(mp -> new MiniAppDtos.RaceUnit(
                        mp.getNumber(),
                        mp.getPlayerName(),
                        mp.getOwnerUserChatId() == null ? null : userService.getUsernameOrFallback(mp.getOwnerUserChatId()),
                        mp.getOwnerUserChatId(),
                        mp.getScore(),
                        0L,
                        0,
                        null
                ))
                .toList();

        MatchPlayer winner = match.getWinner() == null ? null : match.getPlayerByNumber(match.getWinner());
        String winnerName = winner == null ? null : winner.getPlayerName();

        return new MiniAppDtos.RaceResultCard(
                match.getId(),
                match.getType().name(),
                winnerName,
                units
        );
    }

    private Long resolveUserId(Long headerUserId, Long userIdParam) {
        Long userId;
        Long initDataUserId = extractTelegramInitDataUserId();
        Long webAuthUserId = extractWebAuthTokenUserId();
        if (isLocalTestModeActive()) {
            userId = Optional.ofNullable(botProperties.getLocalTestModeUserId()).orElse(740984236L);
            accountService.getByUserId(userId);
            logMiniAppConnectionAttempt(userId, headerUserId, userIdParam, initDataUserId, webAuthUserId, "local_test_mode");
            return userId;
        }

        userId = Optional.ofNullable(headerUserId)
                .or(() -> Optional.ofNullable(initDataUserId))
                .or(() -> Optional.ofNullable(webAuthUserId))
                .or(() -> Optional.ofNullable(userIdParam))
                .orElseThrow(() -> {
                    logMiniAppConnectionAttempt(null, headerUserId, userIdParam, initDataUserId, webAuthUserId, "unresolved");
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Не удалось определить Telegram user id.");
                });
        accountService.getByUserId(userId);
        String source = headerUserId != null ? "x_telegram_user_id_header"
                : initDataUserId != null ? "x_telegram_init_data"
                : webAuthUserId != null ? "x_web_auth_token"
                : "user_id_param";
        logMiniAppConnectionAttempt(userId, headerUserId, userIdParam, initDataUserId, webAuthUserId, source);
        return userId;
    }

    private void logMiniAppConnectionAttempt(
            Long resolvedUserId,
            Long headerUserId,
            Long userIdParam,
            Long initDataUserId,
            Long webAuthUserId,
            String source
    ) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attrs == null ? null : attrs.getRequest();
        if (request == null) {
            log.info(
                    "MiniApp connection attempt without request context: resolvedUserId={}, source={}, headerUserId={}, initDataUserId={}, userIdParam={}",
                    resolvedUserId,
                    source,
                    headerUserId,
                    initDataUserId,
                    userIdParam
            );
            return;
        }

        String initDataHeader = request.getHeader("X-Telegram-Init-Data");
        String tgWebAppDataParam = request.getParameter("tgWebAppData");
        String webAuthHeader = request.getHeader("X-Web-Auth-Token");
        log.info(
                "MiniApp connection attempt: method={}, path={}, query={}, remoteAddr={}, forwardedFor={}, userAgent={}, host={}, origin={}, referer={}, resolvedUserId={}, source={}, headerUserId={}, initDataUserId={}, webAuthUserId={}, userIdParam={}, hasInitDataHeader={}, hasTgWebAppDataParam={}, hasWebAuthHeader={}",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("User-Agent"),
                request.getServerName(),
                request.getHeader("Origin"),
                request.getHeader("Referer"),
                resolvedUserId,
                source,
                headerUserId,
                initDataUserId,
                webAuthUserId,
                userIdParam,
                initDataHeader != null && !initDataHeader.isBlank(),
                tgWebAppDataParam != null && !tgWebAppDataParam.isBlank(),
                webAuthHeader != null && !webAuthHeader.isBlank()
        );
    }

    private void deleteTelegramMessageIfExists(Long userId, Integer messageId) {
        if (userId == null || messageId == null) {
            return;
        }
        bot.deleteMessage(userId, messageId);
    }

    private Long extractTelegramInitDataUserId() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null || attrs.getRequest() == null) {
            return null;
        }

        String initData = Optional.ofNullable(attrs.getRequest().getHeader("X-Telegram-Init-Data"))
                .orElse(attrs.getRequest().getParameter("tgWebAppData"));
        if (initData == null || initData.isBlank()) {
            return null;
        }

        String userJson = null;
        for (String part : initData.split("&")) {
            String[] keyValue = part.split("=", 2);
            if (keyValue.length != 2 || !"user".equals(keyValue[0])) {
                continue;
            }
            userJson = java.net.URLDecoder.decode(keyValue[1], java.nio.charset.StandardCharsets.UTF_8);
            break;
        }

        if (userJson == null || userJson.isBlank()) {
            return null;
        }

        try {
            JSONObject userData = new JSONObject(userJson);
            if (userData.has("id")) {
                return userData.getLong("id");
            }
        } catch (Exception ignored) {
            // fallback below
        }

        Matcher matcher = USER_ID_PATTERN.matcher(userJson);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return null;
    }

    private Long extractWebAuthTokenUserId() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null || attrs.getRequest() == null) {
            return null;
        }

        String authToken = attrs.getRequest().getHeader("X-Web-Auth-Token");
        if (!StringUtils.hasText(authToken)) {
            authToken = attrs.getRequest().getParameter("authToken");
        }
        if (!StringUtils.hasText(authToken)) {
            return null;
        }
        return validateWebAuthToken(authToken);
    }

    private String generateWebAuthToken(Long userId) {
        long exp = (System.currentTimeMillis() / 1000L) + WEB_AUTH_TTL_SECONDS;
        String payload = userId + ":" + exp;
        String signature = hex(hmacSha256(payload, botProperties.getToken()));
        return payload + ":" + signature;
    }

    private Long validateWebAuthToken(String token) {
        try {
            String[] parts = token.split(":");
            if (parts.length != 3) {
                return null;
            }
            long userId = Long.parseLong(parts[0]);
            long exp = Long.parseLong(parts[1]);
            if (userId < 1 || exp < (System.currentTimeMillis() / 1000L)) {
                return null;
            }
            String payload = parts[0] + ":" + parts[1];
            String expected = hex(hmacSha256(payload, botProperties.getToken()));
            if (!constantTimeEquals(expected, parts[2])) {
                return null;
            }
            return userId;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidTelegramLoginPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        Object hashObject = payload.get("hash");
        if (!(hashObject instanceof String hash) || !StringUtils.hasText(hash)) {
            return false;
        }

        Long authDate = parseLong(payload.get("auth_date"));
        if (authDate == null || authDate < 1) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - authDate) > WEB_AUTH_TTL_SECONDS) {
            return false;
        }

        String dataCheckString = payload.entrySet().stream()
                .filter(entry -> !"hash".equals(entry.getKey()))
                .filter(entry -> entry.getValue() != null)
                .filter(entry -> StringUtils.hasText(String.valueOf(entry.getValue())))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));

        byte[] secret = sha256(botProperties.getToken());
        String expectedHash = hex(hmacSha256(dataCheckString, secret));
        return constantTimeEquals(expectedHash, hash.toLowerCase(Locale.ROOT));
    }

    private String extractTelegramAccountLabel(Map<String, Object> payload, Long userId) {
        String username = asString(payload.get("username"));
        if (StringUtils.hasText(username)) {
            return "@" + username;
        }
        String firstName = asString(payload.get("first_name"));
        String lastName = asString(payload.get("last_name"));
        StringJoiner joiner = new StringJoiner(" ");
        if (StringUtils.hasText(firstName)) {
            joiner.add(firstName);
        }
        if (StringUtils.hasText(lastName)) {
            joiner.add(lastName);
        }
        String fullName = joiner.toString().trim();
        return StringUtils.hasText(fullName) ? fullName : "ID " + userId;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private byte[] hmacSha256(String payload, String secret) {
        return hmacSha256(payload, secret == null ? new byte[0] : secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private byte[] hmacSha256(String payload, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot calculate HMAC SHA256", e);
        }
    }

    private byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot calculate SHA-256", e);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private boolean isLocalTestModeActive() {
        if (!botProperties.isLocalTestModeEnabled()) {
            return false;
        }
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null || attrs.getRequest() == null) {
            return false;
        }
        String host = Optional.ofNullable(attrs.getRequest().getServerName()).orElse("").toLowerCase();
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "0:0:0:0:0:0:0:1".equals(host) || "::1".equals(host);
    }
}
