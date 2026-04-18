package my.abdrus.emojirace.bot.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.abdrus.emojirace.api.dto.MiniAppDtos;
import my.abdrus.emojirace.bot.entity.WeeklyPrizeLog;
import my.abdrus.emojirace.bot.repository.AccountRepository;
import my.abdrus.emojirace.bot.repository.PaymentRequestRepository;
import my.abdrus.emojirace.bot.repository.UserRepository;
import my.abdrus.emojirace.bot.repository.WeeklyPrizeLogRepository;
import my.abdrus.emojirace.config.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaderboardService {

    private static final int TOP_LIMIT = 10;
    public static final long WEEKLY_PRIZE_STARS = 100L;
    public static final int WEEKLY_PRIZE_BOOSTERS = 5;

    private final PaymentRequestRepository paymentRequestRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final WeeklyPrizeLogRepository weeklyPrizeLogRepository;

    @Cacheable(CacheConfig.LEADERBOARDS_CACHE)
    public MiniAppDtos.LeaderboardsResponse getLeaderboards() {
        LocalDate currentWeekStart = getCurrentWeekStart();
        Date from = Date.from(currentWeekStart.atStartOfDay().toInstant(ZoneOffset.UTC));
        Date to = Date.from(currentWeekStart.plusDays(7).atStartOfDay().toInstant(ZoneOffset.UTC));

        List<MiniAppDtos.LeaderboardEmojiItem> emojiItems = paymentRequestRepository.findTopWinningEmojis(TOP_LIMIT).stream()
                .map(item -> new MiniAppDtos.LeaderboardEmojiItem(item.getEmoji(), item.getWins()))
                .toList();

        List<MiniAppDtos.LeaderboardPlayerItem> allTimePlayers = mapPlayers(paymentRequestRepository.findTopWinningPlayersAllTime(TOP_LIMIT));
        List<MiniAppDtos.LeaderboardPlayerItem> weeklyPlayers = mapPlayers(paymentRequestRepository.findTopWinningPlayersByPeriod(from, to, TOP_LIMIT));

        MiniAppDtos.LeaderboardPlayerItem weeklyWinner = weeklyPlayers.isEmpty() ? null : weeklyPlayers.get(0);

        return new MiniAppDtos.LeaderboardsResponse(
                emojiItems,
                allTimePlayers,
                weeklyPlayers,
                WEEKLY_PRIZE_STARS,
                WEEKLY_PRIZE_BOOSTERS,
                weeklyWinner == null ? null : weeklyWinner.userId(),
                weeklyWinner == null ? null : weeklyWinner.displayName(),
                from.getTime(),
                to.getTime()
        );
    }

    @Scheduled(cron = "0 10 0 * * MON", zone = "UTC")
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.LEADERBOARDS_CACHE, allEntries = true)
    public void processWeeklyPrize() {
        LocalDate currentWeekStart = getCurrentWeekStart();
        LocalDate previousWeekStart = currentWeekStart.minusWeeks(1);
        if (weeklyPrizeLogRepository.existsByWeekStart(previousWeekStart)) {
            return;
        }

        Date from = Date.from(previousWeekStart.atStartOfDay().toInstant(ZoneOffset.UTC));
        Date to = Date.from(currentWeekStart.atStartOfDay().toInstant(ZoneOffset.UTC));
        List<PaymentRequestRepository.PlayerWonVotesProjection> rating = paymentRequestRepository.findTopWinningPlayersByPeriod(from, to, 1);
        if (rating.isEmpty() || rating.get(0).getUserId() == null) {
            return;
        }

        Long winnerUserId = rating.get(0).getUserId();
        accountRepository.withdrawFunds(accountRepository.findByUserChatId(winnerUserId).orElseThrow().getId(), -WEEKLY_PRIZE_STARS);
        accountRepository.addFreeBustCount(winnerUserId, WEEKLY_PRIZE_BOOSTERS);

        WeeklyPrizeLog logEntry = new WeeklyPrizeLog();
        logEntry.setWeekStart(previousWeekStart);
        logEntry.setWinnerUserChatId(winnerUserId);
        logEntry.setPrizeStars(WEEKLY_PRIZE_STARS);
        logEntry.setPrizeBoosters(WEEKLY_PRIZE_BOOSTERS);
        weeklyPrizeLogRepository.save(logEntry);

        log.info("Weekly prize awarded: userId={}, weekStart={}, stars={}, boosters={}", winnerUserId, previousWeekStart, WEEKLY_PRIZE_STARS, WEEKLY_PRIZE_BOOSTERS);
    }

    private List<MiniAppDtos.LeaderboardPlayerItem> mapPlayers(List<PaymentRequestRepository.PlayerWonVotesProjection> source) {
        return source.stream().map(item -> {
            Long userId = item.getUserId();
            String displayName = userRepository.findByUserChatId(userId)
                    .map(user -> {
                        if (user.getUsername() != null && !user.getUsername().isBlank()) return "@" + user.getUsername();
                        if (user.getFirstName() != null && !user.getFirstName().isBlank()) return user.getFirstName();
                        return "ID " + userId;
                    })
                    .orElse("ID " + userId);
            return new MiniAppDtos.LeaderboardPlayerItem(userId, displayName, item.getWonVotesSum());
        }).toList();
    }

    private LocalDate getCurrentWeekStart() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
