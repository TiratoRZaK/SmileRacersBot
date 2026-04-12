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
            bot.deleteMessageScheduled(userChatId, bot.execute(sendMessage).getMessageId());
        } else if (WithdrawRequestStatus.PAYED.equals(withdrawRequest.getStatus())) {
            SendMessage sendMessage = new SendMessage(userChatId.toString(), "✅ Вывод уже выплачен.");
            bot.deleteMessageScheduled(userChatId, bot.execute(sendMessage).getMessageId());
        } else if (WithdrawRequestStatus.CANCELED.equals(withdrawRequest.getStatus())) {
            SendMessage sendMessage = new SendMessage(userChatId.toString(), "✅ Вывод уже отменён.");
            bot.deleteMessageScheduled(userChatId, bot.execute(sendMessage).getMessageId());
        } else {
            withdrawRequest.setStatus(WithdrawRequestStatus.CANCELED);
            withdrawRequestRepository.save(withdrawRequest);
            accountService.addBalance(withdrawRequest.getUserChatId(), withdrawRequest.getSum());
        }
    }

    public void markPayedByAdmin(Long adminChatId, Long requestId, EmojiRaceBot bot) {
        WithdrawRequest withdrawRequest = withdrawRequestRepository.findById(requestId).orElse(null);
        if (withdrawRequest == null) {
            bot.deleteMessageScheduled(adminChatId, bot.execute(new SendMessage(adminChatId.toString(), "Вывод не найден.")).getMessageId());
            return;
        }
        if (WithdrawRequestStatus.PAYED.equals(withdrawRequest.getStatus())) {
            bot.deleteMessageScheduled(adminChatId, bot.execute(new SendMessage(adminChatId.toString(), "✅ Вывод уже выплачен.")).getMessageId());
            return;
        }
        if (WithdrawRequestStatus.CANCELED.equals(withdrawRequest.getStatus())) {
            bot.deleteMessageScheduled(adminChatId, bot.execute(new SendMessage(adminChatId.toString(), "✅ Вывод уже отменён.")).getMessageId());
            return;
        }

        withdrawRequest.setStatus(WithdrawRequestStatus.PAYED);
        withdrawRequest.setPayedDate(new Date());
        withdrawRequestRepository.save(withdrawRequest);

        SendMessage sendMessage = new SendMessage(withdrawRequest.getUserChatId().toString(),
                "✅ Ваш вывод #" + withdrawRequest.getId() + " успешно выполнен.");
        bot.saveUserNotification(sendMessage, bot.execute(sendMessage));

        bot.deleteMessageScheduled(adminChatId, bot.execute(new SendMessage(adminChatId.toString(),
                "✅ Вывод #" + withdrawRequest.getId() + " отмечен как выплаченный.")).getMessageId());
    }

    public void cancelByAdmin(Long adminChatId, Long requestId, EmojiRaceBot bot) {
        WithdrawRequest withdrawRequest = withdrawRequestRepository.findById(requestId).orElse(null);
        if (withdrawRequest == null) {
            bot.deleteMessageScheduled(adminChatId, bot.execute(new SendMessage(adminChatId.toString(), "Вывод не найден.")).getMessageId());
            return;
        }
        if (WithdrawRequestStatus.PAYED.equals(withdrawRequest.getStatus())) {
            bot.deleteMessageScheduled(adminChatId, bot.execute(new SendMessage(adminChatId.toString(), "✅ Вывод уже выплачен.")).getMessageId());
            return;
        }
        if (WithdrawRequestStatus.CANCELED.equals(withdrawRequest.getStatus())) {
            bot.deleteMessageScheduled(adminChatId, bot.execute(new SendMessage(adminChatId.toString(), "✅ Вывод уже отменён.")).getMessageId());
            return;
        }

        withdrawRequest.setStatus(WithdrawRequestStatus.CANCELED);
        withdrawRequestRepository.save(withdrawRequest);
        accountService.addBalance(withdrawRequest.getUserChatId(), withdrawRequest.getSum());

        SendMessage sendMessage = new SendMessage(withdrawRequest.getUserChatId().toString(),
                "❌ Ваш вывод #" + withdrawRequest.getId()
                        + " отменён администратором. Обратитесь в поддержку.");
        bot.saveUserNotification(sendMessage, bot.execute(sendMessage));
        bot.deleteMessageScheduled(adminChatId, bot.execute(new SendMessage(adminChatId.toString(),
                "✅ Вывод #" + withdrawRequest.getId() + " отменён, сумма возвращена пользователю.")).getMessageId());
    }

    public WithdrawRequest getById(Long requestId) {
        return requestId == null ? null : withdrawRequestRepository.findById(requestId).orElse(null);
    }

    public void markPayedForMiniApp(WithdrawRequest withdrawRequest) {
        if (withdrawRequest == null) {
            return;
        }
        withdrawRequest.setStatus(WithdrawRequestStatus.PAYED);
        withdrawRequest.setPayedDate(new Date());
        withdrawRequestRepository.save(withdrawRequest);
    }

    public void cancelForMiniApp(WithdrawRequest withdrawRequest) {
        if (withdrawRequest == null) {
            return;
        }
        withdrawRequest.setStatus(WithdrawRequestStatus.CANCELED);
        withdrawRequestRepository.save(withdrawRequest);
        accountService.addBalance(withdrawRequest.getUserChatId(), withdrawRequest.getSum());
    }



    public List<WithdrawRequest> findByUser(Long userChatId) {
        return withdrawRequestRepository.findAllByUserChatIdOrderByCreatedDateDesc(userChatId);
    }

    public boolean cancelByUserForMiniApp(Long userChatId, Long requestId) {
        WithdrawRequest withdrawRequest = withdrawRequestRepository.findById(requestId).orElse(null);
        if (withdrawRequest == null) {
            return false;
        }
        if (!withdrawRequest.getUserChatId().equals(userChatId)) {
            return false;
        }
        if (!WithdrawRequestStatus.CREATED.equals(withdrawRequest.getStatus())) {
            return false;
        }
        withdrawRequest.setStatus(WithdrawRequestStatus.CANCELED);
        withdrawRequestRepository.save(withdrawRequest);
        accountService.addBalance(withdrawRequest.getUserChatId(), withdrawRequest.getSum());
        return true;
    }

}
