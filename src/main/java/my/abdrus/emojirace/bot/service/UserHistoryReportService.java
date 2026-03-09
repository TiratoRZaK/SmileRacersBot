package my.abdrus.emojirace.bot.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.entity.BalanceTopup;
import my.abdrus.emojirace.bot.entity.BotUser;
import my.abdrus.emojirace.bot.entity.PaymentRequest;
import my.abdrus.emojirace.bot.entity.WithdrawRequest;
import my.abdrus.emojirace.bot.enumeration.PaymentRequestStatus;
import my.abdrus.emojirace.bot.enumeration.WithdrawRequestStatus;
import my.abdrus.emojirace.bot.repository.BalanceTopupRepository;
import my.abdrus.emojirace.bot.repository.PaymentRequestRepository;
import my.abdrus.emojirace.bot.repository.UserRepository;
import my.abdrus.emojirace.bot.repository.WithdrawRequestRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;

@Service
public class UserHistoryReportService {

    private static final int MAX_LINES_IN_MESSAGE = 30;

    @Autowired
    private PaymentRequestRepository paymentRequestRepository;
    @Autowired
    private WithdrawRequestRepository withdrawRequestRepository;
    @Autowired
    private BalanceTopupRepository balanceTopupRepository;
    @Autowired
    private UserRepository userRepository;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");

    public void sendHistory(Long requesterChatId, Long targetUserId, EmojiRaceBot bot) {
        BotUser targetUser = userRepository.findByUserChatId(targetUserId).orElse(null);
        String targetLabel = targetUser != null && targetUser.getUsername() != null && !targetUser.getUsername().isBlank()
                ? "@" + targetUser.getUsername()
                : String.valueOf(targetUserId);

        List<HistoryItem> history = loadHistory(targetUserId);
        if (history.isEmpty()) {
            bot.execute(new SendMessage(requesterChatId.toString(), "История пользователя " + targetLabel + " пока пуста."));
            return;
        }

        if (history.size() > MAX_LINES_IN_MESSAGE) {
            sendExcel(requesterChatId, targetUserId, targetLabel, history, bot);
            return;
        }

        StringBuilder text = new StringBuilder("📒 История операций пользователя ")
                .append(targetLabel).append("\n\n");
        for (HistoryItem item : history) {
            text.append("• ").append(dateFormat.format(item.createdDate))
                    .append(" | ").append(item.operation)
                    .append(" | ").append(item.amount).append(" ⭐")
                    .append(" | ").append(item.details)
                    .append("\n");
        }
        bot.execute(new SendMessage(requesterChatId.toString(), text.toString()));
    }

    private List<HistoryItem> loadHistory(Long userId) {
        List<HistoryItem> result = new ArrayList<>();

        for (PaymentRequest request : paymentRequestRepository.findAllByUserChatIdOrderByCreatedDateDesc(userId)) {
            String details = "Матч #" + request.getMatchPlayer().getMatch().getId()
                    + ", смайл " + request.getMatchPlayer().getPlayerName()
                    + ", статус: " + mapVoteStatus(request);
            result.add(new HistoryItem(request.getCreatedDate(), "Голос", -request.getSum(), details));
        }

        for (WithdrawRequest request : withdrawRequestRepository.findAllByUserChatIdOrderByCreatedDateDesc(userId)) {
            String details = switch (request.getStatus()) {
                case PAYED, COMPLETED -> "Вывод подтверждён";
                case CANCELED -> "Вывод отменён";
                case CREATED -> "Вывод в обработке";
            };
            long amount = request.getStatus() == WithdrawRequestStatus.CANCELED ? request.getSum() : -request.getSum();
            result.add(new HistoryItem(request.getCreatedDate(), "Вывод", amount, details + " (#" + request.getId() + ")"));
        }

        for (BalanceTopup topup : balanceTopupRepository.findAllByUserChatIdOrderByCreatedDateDesc(userId)) {
            result.add(new HistoryItem(topup.getCreatedDate(), "Пополнение", topup.getSum(), "Источник: " + topup.getSource()));
        }

        result.sort(Comparator.comparing((HistoryItem h) -> h.createdDate).reversed());
        return result;
    }

    private String mapVoteStatus(PaymentRequest request) {
        if (request.getStatus() == PaymentRequestStatus.WAIT_PAYMENT) {
            return "ожидает оплаты";
        }
        if (request.getStatus() == PaymentRequestStatus.PAYED) {
            return "гонка не завершена";
        }
        return request.isToWinner() ? "выигрыш" : "проигрыш";
    }

    private void sendExcel(Long requesterChatId, Long userId, String userLabel, List<HistoryItem> history, EmojiRaceBot bot) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("История операций");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Дата");
            header.createCell(1).setCellValue("Операция");
            header.createCell(2).setCellValue("Сумма ⭐");
            header.createCell(3).setCellValue("Детали");

            for (int i = 0; i < history.size(); i++) {
                HistoryItem item = history.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(dateFormat.format(item.createdDate));
                row.createCell(1).setCellValue(item.operation);
                row.createCell(2).setCellValue(item.amount);
                row.createCell(3).setCellValue(item.details);
            }
            for (int i = 0; i < 4; i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(output);

            SendDocument document = new SendDocument();
            document.setChatId(requesterChatId.toString());
            document.setCaption("История операций пользователя " + userLabel + " (" + userId + ")");
            document.setDocument(new InputFile(
                    new ByteArrayInputStream(output.toByteArray()),
                    "history_" + userId + ".xlsx"
            ));
            bot.execute(document);
        } catch (Exception e) {
            bot.execute(new SendMessage(requesterChatId.toString(), "Не удалось сформировать Excel-отчёт: " + e.getMessage()));
        }
    }

    private record HistoryItem(Date createdDate, String operation, Long amount, String details) {
    }
}
