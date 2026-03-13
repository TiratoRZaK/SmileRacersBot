package my.abdrus.emojirace.api.controller;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import my.abdrus.emojirace.api.dto.MiniAppDtos;
import my.abdrus.emojirace.bot.entity.Account;
import my.abdrus.emojirace.bot.entity.Match;
import my.abdrus.emojirace.bot.entity.PaymentRequest;
import my.abdrus.emojirace.bot.entity.Player;
import my.abdrus.emojirace.bot.entity.Race;
import my.abdrus.emojirace.bot.enumeration.BusterType;
import my.abdrus.emojirace.bot.enumeration.MatchStatus;
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
import my.abdrus.emojirace.bot.service.UserService;
import my.abdrus.emojirace.bot.service.WithdrawService;
import my.abdrus.emojirace.config.BotProperties;
import org.json.JSONObject;
import my.abdrus.emojirace.config.ChannelProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
@RequestMapping("/api/miniapp")
@RequiredArgsConstructor
public class MiniAppController {

    private static final long FAVORITE_REPLACE_COST = 150L;
    private static final Pattern USER_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

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
    private final ChannelProperties channelProperties;
    private final BotProperties botProperties;

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
                .findFirstByStatusInOrderByCreatedDateDesc(List.of(MatchStatus.CREATED, MatchStatus.LIVE))
                .orElse(null);

        MiniAppDtos.RaceCard raceCard = toRaceCard(selectedMatch, activeRace);

        List<String> emojis = playerRepository.findAll().stream()
                .map(Player::getName)
                .sorted(Comparator.naturalOrder())
                .toList();

        return new MiniAppDtos.BootstrapResponse(
                userId,
                isLocalTestModeActive(),
                account.getBalance(),
                account.getFreeBustCount(),
                user.getFavoritePlayer() == null ? null : user.getFavoritePlayer().getName(),
                raceCard,
                emojis
        );
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
        return new MiniAppDtos.ActionResponse(true, "Смайл добавлен в очередь. Позиция: " + position);
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
            return new MiniAppDtos.ActionResponse(true, "Батл создан: #" + battle.getId());
        } catch (PaymentException e) {
            return new MiniAppDtos.ActionResponse(false, e.getMessage());
        }
    }

    @GetMapping("/help")
    public MiniAppDtos.ActionResponse help() {
        return new MiniAppDtos.ActionResponse(true, channelProperties.getHelpLink());
    }

    private MiniAppDtos.RaceCard toRaceCard(Match match, Race activeRace) {
        if (match == null) {
            return null;
        }
        List<MiniAppDtos.RaceUnit> units = Optional.ofNullable(match.getMatchPlayers()).orElse(List.of()).stream()
                .sorted(Comparator.comparingInt(mp -> mp.getNumber() == null ? 0 : mp.getNumber()))
                .map(mp -> new MiniAppDtos.RaceUnit(
                        mp.getNumber(),
                        mp.getPlayerName(),
                        activeRace != null && activeRace.getMatch().getId().equals(match.getId())
                                ? Math.round(activeRace.getScoreByNumber(mp.getNumber()))
                                : 0L
                ))
                .toList();

        Long trackLength = activeRace != null && activeRace.getMatch().getId().equals(match.getId())
                ? Math.round(activeRace.getRaceSize())
                : null;

        return new MiniAppDtos.RaceCard(match.getId(), match.getStatus().name(), match.getType().name(), trackLength, units);
    }

    private Long resolveUserId(Long headerUserId, Long userIdParam) {
        Long userId;
        if (isLocalTestModeActive()) {
            userId = Optional.ofNullable(botProperties.getLocalTestModeUserId()).orElse(740984236L);
            accountService.getByUserId(userId);
            return userId;
        }

        userId = Optional.ofNullable(headerUserId)
                .or(() -> Optional.ofNullable(extractTelegramInitDataUserId()))
                .or(() -> Optional.ofNullable(userIdParam))
                .orElseThrow(() -> new IllegalArgumentException("Не удалось определить Telegram user id."));
        accountService.getByUserId(userId);
        return userId;
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
