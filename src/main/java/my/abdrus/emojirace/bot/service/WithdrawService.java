package my.abdrus.emojirace.bot.service;

import java.util.Date;
import java.util.UUID;

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

    public Long sendWithdrawRequestToAdmin(Long userChatId, Long amount, Date createdDate) {
        WithdrawRequest request = WithdrawRequest.builder()
                .createdDate(createdDate)
                .status(WithdrawRequestStatus.CREATED)
                .sum(amount)
                .userChatId(userChatId)
                .build();
        return withdrawRequestRepository.save(request).getId();
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
        }
    }

}
