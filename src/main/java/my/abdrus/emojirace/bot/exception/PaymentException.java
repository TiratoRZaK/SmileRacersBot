package my.abdrus.emojirace.bot.exception;

import my.abdrus.emojirace.bot.enumeration.PaymentExceptionType;

public class PaymentException extends RuntimeException {

    private final PaymentExceptionType type;

    public PaymentException(PaymentExceptionType type) {
        this.type = type;
    }

    @Override
    public String getMessage() {
        return switch (type) {
            case ACCOUNT_WITH_BALANCE_NOT_FOUND -> "Средств на балансе недостаточно.";
            case BALANCE_UPDATED -> "Баланс был обновлён. Средств недостаточно.";
        };
    }
}
