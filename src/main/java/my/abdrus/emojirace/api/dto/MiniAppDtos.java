package my.abdrus.emojirace.api.dto;

import java.util.List;

public class MiniAppDtos {

    public record BootstrapResponse(
            Long userId,
            Long balance,
            Integer freeBoosters,
            String favoriteEmoji,
            RaceCard race,
            List<String> allEmojis
    ) {}

    public record RaceCard(
            Long matchId,
            String status,
            String type,
            Long trackLength,
            List<RaceUnit> units
    ) {}

    public record RaceUnit(
            Integer playerNumber,
            String playerName,
            Long score
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

    public record CreateBattleRequest(String playerName, Long stake) {}

    public record ActionResponse(boolean success, String message) {}
}
