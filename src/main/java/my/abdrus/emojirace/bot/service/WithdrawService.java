package my.abdrus.emojirace.bot.service;

import java.util.Date;
import java.util.List;

import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.entity.WithdrawRequest;
import my.abdrus.emojirace.bot.enumeration.WithdrawRequestStatus;
import my.abdrus.emojirace.bot.repository.WithdrawRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

@Service
public class WithdrawService {

    @Autowired
    private WithdrawRequestRepository withdrawRequestRepository;

    @Autowired
    private AccountService accountService;

    public Long sendWithdrawRequestToAdmin(Long userChatId, Long amount, Date createdDate) {
        WithdrawRequest request = WithdrawRequest.builder()
                .createdDate(createdDate)
                .status(WithdrawRequestStatus.CREATED)
                .sum(amount)
                .userChatId(userChatId)
                .build();
        accountService.pay(userChatId, amount);
        return withdrawRequestRepository.save(request).getId();
    }

    public List<WithdrawRequest> getCreatedRequests() {
        return withdrawRequestRepository.findAllByStatusOrderByIdAsc(WithdrawRequestStatus.CREATED);
    }

    public void cancelById(Long userChatId, Long requestId, EmojiRaceBot bot) {
        WithdrawRequest withdrawRequest = withdrawRequestRepository.findById(requestId).orElse(null);
        if (withdrawRequest == null) {
            SendMessage sendMessage = new SendMessage(userChatId.toString(), "Вывод не найден.");
            bot.execute(sendMessage);
        } else if (WithdrawRequestStatus.PAYED.equals(withdrawRequest.getStatus())) {
            SendMessage sendMessage = new SendMessage(userChatId.toString(), "Вывод уже выплачен.");
            bot.execute(sendMessage);
        } else if (WithdrawRequestStatus.CANCELED.equals(withdrawRequest.getStatus())) {
            SendMessage sendMessage = new SendMessage(userChatId.toString(), "Вывод уже отменён.");
            bot.execute(sendMessage);
        } else {
            withdrawRequest.setStatus(WithdrawRequestStatus.CANCELED);
            withdrawRequestRepository.save(withdrawRequest);
            accountService.addBalance(withdrawRequest.getUserChatId(), withdrawRequest.getSum());
        }
    }

    public void markPayedByAdmin(Long adminChatId, Long requestId, EmojiRaceBot bot) {
        WithdrawRequest withdrawRequest = withdrawRequestRepository.findById(requestId).orElse(null);
        if (withdrawRequest == null) {
            bot.execute(new SendMessage(adminChatId.toString(), "Вывод не найден."));
            return;
        }
        if (WithdrawRequestStatus.PAYED.equals(withdrawRequest.getStatus())) {
            bot.execute(new SendMessage(adminChatId.toString(), "Вывод уже выплачен."));
            return;
        }
        if (WithdrawRequestStatus.CANCELED.equals(withdrawRequest.getStatus())) {
            bot.execute(new SendMessage(adminChatId.toString(), "Вывод уже отменён."));
            return;
        }

        withdrawRequest.setStatus(WithdrawRequestStatus.PAYED);
        withdrawRequest.setPayedDate(new Date());
        withdrawRequestRepository.save(withdrawRequest);

        bot.execute(new SendMessage(withdrawRequest.getUserChatId().toString(),
                "✅ Ваш вывод #" + withdrawRequest.getId() + " успешно выполнен."));
        bot.execute(new SendMessage(adminChatId.toString(),
                "Вывод #" + withdrawRequest.getId() + " отмечен как выплаченный."));
    }

    public void cancelByAdmin(Long adminChatId, Long requestId, EmojiRaceBot bot) {
        WithdrawRequest withdrawRequest = withdrawRequestRepository.findById(requestId).orElse(null);
        if (withdrawRequest == null) {
            bot.execute(new SendMessage(adminChatId.toString(), "Вывод не найден."));
            return;
        }
        if (WithdrawRequestStatus.PAYED.equals(withdrawRequest.getStatus())) {
            bot.execute(new SendMessage(adminChatId.toString(), "Вывод уже выплачен."));
            return;
        }
        if (WithdrawRequestStatus.CANCELED.equals(withdrawRequest.getStatus())) {
            bot.execute(new SendMessage(adminChatId.toString(), "Вывод уже отменён."));
            return;
        }

        withdrawRequest.setStatus(WithdrawRequestStatus.CANCELED);
        withdrawRequestRepository.save(withdrawRequest);
        accountService.addBalance(withdrawRequest.getUserChatId(), withdrawRequest.getSum());

        bot.execute(new SendMessage(withdrawRequest.getUserChatId().toString(),
                "❌ Ваш вывод #" + withdrawRequest.getId()
                        + " отменён администратором. Обратитесь в поддержку."));
        bot.execute(new SendMessage(adminChatId.toString(),
                "Вывод #" + withdrawRequest.getId() + " отменён, сумма возвращена пользователю."));
    }

}
