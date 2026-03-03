package my.abdrus.emojirace.bot.service;

import jakarta.validation.constraints.NotNull;
import my.abdrus.emojirace.bot.entity.Account;
import my.abdrus.emojirace.bot.entity.BotUser;
import my.abdrus.emojirace.bot.entity.PaymentRequest;
import my.abdrus.emojirace.bot.enumeration.PaymentExceptionType;
import my.abdrus.emojirace.bot.exception.PaymentException;
import my.abdrus.emojirace.bot.repository.AccountRepository;
import my.abdrus.emojirace.bot.repository.PaymentRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private PaymentRequestRepository paymentRequestRepository;
    @Autowired
    private UserService userService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void pay(@NotNull PaymentRequest paymentRequest) throws PaymentException {
        Account account = accountRepository
                .findAvailableAccount(paymentRequest.getUserChatId(), paymentRequest.getSum())
                .orElseThrow(() -> new PaymentException(PaymentExceptionType.ACCOUNT_WITH_BALANCE_NOT_FOUND));
        int result = accountRepository.withdrawFunds(account.getId(), paymentRequest.getSum());
        if (paymentRequest.getId() != null) {
            paymentRequestRepository.setPayedStatus(paymentRequest.getId());
        }
        if (result == 0) {
            throw new PaymentException(PaymentExceptionType.BALANCE_UPDATED);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void pay(Long userId, Long sum) throws PaymentException {
        Account account = accountRepository
                .findAvailableAccount(userId, sum)
                .orElseThrow(() -> new PaymentException(PaymentExceptionType.ACCOUNT_WITH_BALANCE_NOT_FOUND));
        int result = accountRepository.withdrawFunds(account.getId(), sum);
        if (result == 0) {
            throw new PaymentException(PaymentExceptionType.BALANCE_UPDATED);
        }
    }

    public Account getByUserId(Long userId) {
        return accountRepository
                .findByUserChatId(userId)
                .orElseGet(() -> {
                    BotUser user = userService.createIfNeed(userId);
                    return accountRepository.save(new Account(userId, user));
                });
    }

    public void linkToUser(Long userId) {
        BotUser user = userService.createIfNeed(userId);
        Account account = getByUserId(userId);
        if (account.getUser() == null) {
            account.setUser(user);
            accountRepository.save(account);
        }
    }

    public Long getBalanceByUserChatId(Long userId) {
        return getByUserId(userId).getBalance();
    }

    public boolean addBalance(Long userId, Long sum) {
        return accountRepository.withdrawFunds(getByUserId(userId).getId(), -sum) == 1;
    }
}
