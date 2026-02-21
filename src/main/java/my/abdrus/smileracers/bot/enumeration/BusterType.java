package my.abdrus.smileracers.bot.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum BusterType {
    NONE(0L, "", ""),
    BUST(10L, "\uD83D\uDC07", "bust"),
    SLOW(10L, "\uD83D\uDC22", "slow"),
    SHIELD(40L, "\uD83E\uDE96", "shield");

    private final Long cost;
    private final String name;
    private final String query;
}