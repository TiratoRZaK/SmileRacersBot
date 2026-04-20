package my.abdrus.emojirace.api.dto;

import java.util.List;

public class MiniAppDtos {

    public record BootstrapResponse(
            Long userId,
            boolean isAdmin,
            boolean localTestMode,
            Integer generationIntervalMinutes,
            Long balance,
            Integer freeBoosters,
            String favoriteEmoji,
            RaceCard race,
            RaceCard myBattle
    ) {}

    public record BootstrapExtrasResponse(
            List<String> allEmojis,
            List<UiNotification> notifications,
            List<String> adminUsernames
    ) {}

    public record UiNotification(
            Long id,
            String text,
            Long createdAtMs
    ) {}

    public record RaceCard(
            Long matchId,
            String status,
            String type,
            Long trackLength,
            Long battleStake,
            boolean battleStartRequested,
            String inviteLink,
            List<RaceUnit> units
    ) {}

    public record RaceUnit(
            Integer playerNumber,
            String playerName,
            String ownerName,
            Long ownerUserId,
            Long score,
            Long myVotes,
            Integer playerShields,
            String activeBooster
    ) {}

    public record RaceResultCard(
            Long matchId,
            String type,
            String winnerName,
            List<RaceUnit> units
    ) {}

    public record VoteRequest(Long matchId, Integer playerNumber, Long amount) {}

    public record BoostRequest(Integer playerNumber, String type) {}

    public record FavoriteRequest(String playerName) {}

    public record QueueRequest(String playerName) {}

    public record WithdrawRequest(Long amount) {}

    public record TopupRequest(Long amount) {}

    public record CancelWithdrawRequest(Long requestId) {}

    public record TopupLinkResponse(boolean success, String message, String invoiceLink) {}

    public record WithdrawItem(Long id, Long amount, String status, Long createdAtMs) {}

    public record ActiveWithdrawsResponse(List<WithdrawItem> items) {}

    public record HistoryItem(Long createdAtMs, String operation, Long amount, String details) {}

    public record HistoryResponse(List<HistoryItem> items) {}

    public record RecentResultsResponse(List<RaceResultCard> items) {}

    public record LeaderboardEmojiItem(
            String emoji,
            Long wins
    ) {}

    public record LeaderboardPlayerItem(
            Long userId,
            String displayName,
            Long wonVotesSum
    ) {}

    public record LeaderboardsResponse(
            List<LeaderboardEmojiItem> emojiWinners,
            List<LeaderboardPlayerItem> playerWinnersAllTime,
            List<LeaderboardPlayerItem> playerWinnersWeekly,
            Long weeklyPrizeStars,
            Integer weeklyPrizeBoosters,
            Long weeklyWinnerUserId,
            String weeklyWinnerName,
            Long weeklyPeriodStart,
            Long weeklyPeriodEnd
    ) {}

    public record CreateBattleRequest(String playerName, Long stake) {}

    public record StartBattleRequest(Long matchId) {}

    public record RemoveBattleParticipantRequest(Long matchId, Integer playerNumber) {}

    public record JoinBattleRequest(Long matchId, String playerName) {}

    public record InviteUserToBattleRequest(Long matchId, String username) {}

    public record CancelBattleRequest(Long matchId) {}

    public record DragRaceStartRequest(
            String difficulty,
            Long stake,
            String buyAirbagBy
    ) {}

    public record DragRaceChoiceRequest(String branchId) {}

    public record DragRaceBranchCard(
            String branchId,
            String title,
            String hint,
            Double previewSuccessChance
    ) {}

    public record DragRaceEventCard(
            String code,
            String title,
            String description,
            List<DragRaceBranchCard> branches
    ) {}

    public record DragRaceStateResponse(
            boolean success,
            String message,
            String runId,
            String difficulty,
            Long stake,
            Integer currentEventIndex,
            Integer totalEvents,
            boolean finished,
            boolean runSuccess,
            boolean airbagAvailable,
            boolean airbagConsumed,
            Long projectedReward,
            Long payout,
            DragRaceEventCard currentEvent
    ) {
        public static DragRaceStateResponse error(String message) {
            return new DragRaceStateResponse(false, message, null, null, null, null, null, false, false, false, false, 0L, 0L, null);
        }

        public static DragRaceStateResponse empty(String message) {
            return new DragRaceStateResponse(true, message, null, null, null, null, null, false, false, false, false, 0L, 0L, null);
        }
    }

    public record DeleteNotificationRequest(Long notificationId) {}

    public record TelegramAuthConfigResponse(String botUsername) {}

    public record TelegramWebAuthResponse(
            boolean success,
            String message,
            Long userId,
            String authToken,
            String accountLabel
    ) {}

    public record WebCredentialsRequest(String username, String password) {}

    public record AuthStatusRequest(String username) {}

    public record AuthStatusResponse(
            boolean success,
            String message,
            boolean userExists,
            boolean requiresPasswordSetup
    ) {}

    public record WebAuthResponse(
            boolean success,
            String message,
            Long userId,
            String authToken,
            String accountLabel
    ) {}

    public record AdminWithdrawItem(
            Long id,
            Long userId,
            String username,
            Long amount,
            String status,
            Long createdAtMs
    ) {}

    public record AdminWithdrawsResponse(List<AdminWithdrawItem> items) {}

    public record AdminWithdrawActionRequest(Long requestId) {}

    public record AdminBalanceAdjustRequest(String username, Long amount) {}

    public record ActionResponse(boolean success, String message) {}
}
