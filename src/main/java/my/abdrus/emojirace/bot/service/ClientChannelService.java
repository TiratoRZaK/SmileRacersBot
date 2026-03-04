package my.abdrus.emojirace.bot.service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import my.abdrus.emojirace.bot.EmojiRaceBot;
import my.abdrus.emojirace.bot.entity.BotUser;
import my.abdrus.emojirace.bot.entity.DependMessageCode;
import my.abdrus.emojirace.bot.entity.PaymentRequest;
import my.abdrus.emojirace.bot.entity.Player;
import my.abdrus.emojirace.bot.entity.WithdrawRequest;
import my.abdrus.emojirace.bot.enumeration.DependMessage;
import my.abdrus.emojirace.bot.enumeration.PaymentRequestStatus;
import my.abdrus.emojirace.bot.exception.PaymentException;
import my.abdrus.emojirace.bot.repository.PaymentRequestRepository;
import my.abdrus.emojirace.bot.repository.PlayerRepository;
import my.abdrus.emojirace.bot.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Contact;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

@Service
public class ClientChannelService extends ChannelService {

    @Autowired
    private PaymentRequestRepository paymentRequestRepository;
    @Autowired
    private MatchGenerationService matchGenerationService;
    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private PlayerRepository playerRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private StateService stateService;
    @Autowired
    private WithdrawService withdrawService;
    @Autowired
    private DependMessageService dependMessageService;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");

    @Override
    public void updateProcess(Update update, EmojiRaceBot bot) {
        if (update.hasMessage()) {
            Message message = update.getMessage();
            Long chatId = message.getChatId();

            if (update.getMessage().hasSuccessfulPayment()) {
                SuccessfulPayment successfulPayment = update.getMessage().getSuccessfulPayment();
                String invoicePayload = successfulPayment.getInvoicePayload();
                String[] s = invoicePayload.split("_");
                Long userId = Long.valueOf(s[1]);
                Long amount = Long.valueOf(s[2]);
                if (accountService.addBalance(userId, amount)) {
                    Integer messageId = bot.execute(
                            new SendMessage(userId.toString(), "Баланс успешно пополнен на " + amount + " ⭐"))
                            .getMessageId();
                    bot.deleteMessageScheduled(userId, messageId);
                } else {
                    Integer messageId = bot.execute(
                            new SendMessage(userId.toString(),"Произошла ошибка при оплате. Используйте кнопку 'Помощь' в боте, для решения проблемы"))
                            .getMessageId();
                    bot.deleteMessageScheduled(userId, messageId);
                }
            } else if (message.hasText()) {
                textProcess(message, chatId, bot);
            } else if (message.hasContact()) {
                contactProcess(message, chatId, bot);
            }
        } else if (update.hasCallbackQuery()) {
            callbackQueryProcess(update.getCallbackQuery(), bot);
        } else if (update.hasPreCheckoutQuery()) {
            preCallbackQueryProcess(update.getPreCheckoutQuery(), bot);
        }
    }

    private void contactProcess(Message message, Long chatId, EmojiRaceBot bot) {
        bot.deleteMessage(chatId, message.getMessageId());
        Contact contact = message.getContact();
        boolean isAdmin = userService.isAdmin(chatId);
        if (!isAdmin) {
            bot.deleteMessage(chatId, message.getMessageId());
            return;
        }
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(message.getFrom().getId());
        sendMessage.setText("👥 Работа с пользователем");
        sendMessage.setReplyMarkup(createUserRequestKeyboard(contact.getUserId()));
        Integer messageId = bot.execute(sendMessage).getMessageId();
        bot.deleteMessageScheduled(message.getFrom().getId(), messageId, 120_000);
    }

    private void textProcess(Message message, Long chatId, EmojiRaceBot bot) {
        String text = message.getText();
        Integer messageId = message.getMessageId();
        bot.deleteMessage(chatId, messageId);

        userService.addInfoIfNeed(message.getFrom());
        accountService.linkToUser(message.getFrom().getId());

        StateService.Session session = stateService.getSession(chatId);
        if (session.state == StateService.State.WAITING_FOR_AMOUNT_DEP) {
            try {
                long amount = Long.parseLong(text);
                bot.deleteMessageScheduled(chatId, invoiceService.sendDepositInvoice(chatId, amount));
                stateService.clear(chatId);
            } catch (NumberFormatException e) {
                SendMessage msg = new SendMessage(
                        chatId.toString(), "❌ Введено не корректное число, повторите попытку пополнения через кнопку Баланс в меню.");
                bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId());
                stateService.clear(chatId);
            }
            return;
        } else if (session.state == StateService.State.WAITING_FOR_AMOUNT_WITHDRAW) {
            try {
                long amount = Long.parseLong(text);
                if (amount < 100) {
                    SendMessage msg = new SendMessage(
                            chatId.toString(), "❌ Введено не корректное число. Сумма меньше минимальной.");
                    bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId());
                    stateService.clear(chatId);
                    return;
                } else if (accountService.getBalanceByUserChatId(chatId).compareTo(amount) < 0) {
                    SendMessage msg = new SendMessage(
                            chatId.toString(), "❌ Введено не корректное число. Сумма больше доступной на балансе.");
                    bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId());
                    stateService.clear(chatId);
                    return;
                }
                Date createdDate = new Date();
                try {
                    Long requestId = withdrawService.sendWithdrawRequestToAdmin(chatId, amount, createdDate);
                    SendMessage msg = new SendMessage(
                            chatId.toString(), "✅ Запрос на вывод #" + requestId + " на " + amount + "⭐" + "от " + dateFormat.format(createdDate) + " отправлен администраторам. \nОжидайте очереди. С вами свяжутся в течении 3 суток. \nВ основном это занимает до 24 часов.");
                    msg.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(InlineKeyboardButton.builder()
                            .callbackData("cancelWithdraw_" + requestId)
                            .text("Отменить вывод")
                            .build()))));
                    bot.execute(msg);
                } catch (PaymentException e) {
                    SendMessage msg = new SendMessage(
                            chatId.toString(), "❌ Ошибка списания с баланса. Повторите позже или обратитесь в поддержку.");
                    bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId());
                }
                stateService.clear(chatId);
            } catch (NumberFormatException e) {
                SendMessage msg = new SendMessage(
                        chatId.toString(), "❌ Введено не корректное число, повторите попытку пополнения через кнопку Баланс в меню.");
                bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId());
                stateService.clear(chatId);
            }
            return;
        }

        if (text.startsWith("plus_")) {
            String[] s = text.split("_");
            Long userId = Long.valueOf(s[1]);
            int sum = Integer.parseInt(s[2]);
            if (accountService.addBalance(userId, (long) sum)) {
                bot.deleteMessageScheduled(chatId, bot.execute(new SendMessage(message.getChatId().toString(), "Баланс успешно пополнен")).getMessageId());
            }
        } else if (text.startsWith("minus_")) {
            String[] s = text.split("_");
            Long userId = Long.valueOf(s[1]);
            int sum = Integer.parseInt(s[2]);
            if (accountService.addBalance(userId, -(long) sum)) {
                bot.deleteMessageScheduled(chatId, bot.execute(new SendMessage(message.getChatId().toString(), "Баланс успешно уменьшен")).getMessageId());
            }
        } else if (text.equals("💰 Баланс")) {
            Long balance = accountService.getBalanceByUserChatId(chatId);
            SendMessage msg = new SendMessage(chatId.toString(), "Ваш баланс составляет:\n" + balance + " ⭐");
            msg.setReplyMarkup(createDepositKeyboard(chatId));
            bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId(), channelProperties.getDefaultDeleteMessageMenuDelay());
        } else if (text.equals("😎 Выбрать любимый смайл")) {
            List<Player> players = new ArrayList<>(playerRepository.findAll());
            boolean isFirstMessage = true;
            while (!players.isEmpty()) {
                int count = Math.min(100, players.size());
                List<Player> subList = new ArrayList<>(players.subList(0, count));
                Integer sendMessageId = bot.execute(sendSelectPlayerMessage(subList, isFirstMessage, true, chatId)).getMessageId();
                dependMessageService.putDependMessage(chatId, DependMessage.builder()
                        .messageId(sendMessageId)
                        .chatId(chatId)
                        .code(DependMessageCode.SELECT_FAV_PLAYER)
                        .build());
                players.subList(0, count).clear();
                isFirstMessage = false;
            }
        } else if (text.startsWith("\uD83D\uDE33 Сменить любимый смайл")) {
            List<Player> players = new ArrayList<>(playerRepository.findAll());
            boolean isFirstMessage = true;
            while (!players.isEmpty()) {
                int count = Math.min(100, players.size());
                List<Player> subList = new ArrayList<>(players.subList(0, count));
                Integer sendMessageId = bot.execute(sendSelectPlayerMessage(subList, isFirstMessage, false, chatId)).getMessageId();
                dependMessageService.putDependMessage(chatId, DependMessage.builder()
                        .messageId(sendMessageId)
                        .chatId(chatId)
                        .code(DependMessageCode.SELECT_FAV_PLAYER)
                        .build());
                players.subList(0, count).clear();
                isFirstMessage = false;
            }
        } else if (text.equals("🆘 Помощь")) {
            SendMessage msg = new SendMessage(chatId.toString(), "Обратитесь к владельцу канала:");
            message.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(InlineKeyboardButton.builder()
                    .text("👤 Связаться с владельцем")
                    .url(channelProperties.getHelpLink())
                    .build()))));
            bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId(), channelProperties.getDefaultDeleteMessageMenuDelay());
        } else if (text.equals("📤 Выводы") && userService.isAdmin(chatId)) {
            sendCreatedWithdraws(chatId, bot);
        }  else if (text.startsWith("\uD83D\uDC4A Отправить ")) {
            try {
                accountService.pay(message.getChatId(), 10L);
                int queueCount = matchGenerationService.addPlayerToQueue(userRepository.findByUserChatId(chatId).get().getFavoritePlayer());
                SendMessage msg = new SendMessage(chatId.toString(), "Смайл успешно отправлен в очередь! Перед вами ещё " + queueCount + " смайлов. Ожидайте.");
                bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId());
            } catch (Exception e) {
                SendMessage msg = new SendMessage(chatId.toString(), "Ошибка оплаты. " + e.getMessage());
                bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId());
            }
        } else if (text.equals("\uD83D\uDC4A Показать текущую битву")) {
            bot.deleteMessageScheduled(chatId,
                    matchService.sendActiveMatchStateToChannel(chatId, false, bot),
                    channelProperties.getDefaultDeleteMessageMenuDelay());
        } else if (text.equals("/start")) {
            sendPersistentKeyboard(message.getFrom(), chatId, bot);
        }
    }

    private SendMessage sendSelectPlayerMessage(List<Player> players, boolean isFirstMessage, boolean isFirstSelect, Long chatId) {
        String text = "Следующие смайлы:";
        if (isFirstMessage) {
            text =  """
                ❗❗❗ Внимание! ❗❗❗
                Любимый смайл выбирается бесплатно только один раз!
                В следующий раз смена любимого смайла будет стоить 150 ⭐
                
                Выбор любимого смайла открывает возможности:
                 - При ставке по линии на ваш любимый смайл, в случае его победы получите + 1 \uD83D\uDC07 подарок
                 - За 10 ⭐ ставить свой любимый смайл в очередь на участие в следующей битве
                """;
        }
        SendMessage msg = new SendMessage(chatId.toString(), text);
        msg.setReplyMarkup(createSelectPlayerKeyboard(players, chatId, isFirstSelect));
        return msg;
    }

    @Override
    public boolean callbackQueryProcess(CallbackQuery callbackQuery, EmojiRaceBot bot) {
        boolean isProcessed = super.callbackQueryProcess(callbackQuery, bot);
        if (isProcessed) {
            return true;
        }
        String query = callbackQuery.getData();
        Long userChatId = callbackQuery.getFrom().getId();

        if (query.startsWith("buy_")) {
            String[] s = query.split("_");
            long amount = Long.parseLong(s[1]);
            long matchId = Long.parseLong(s[2]);
            Integer playerNumber = Integer.parseInt(s[3]);

            matchRepository.findById(matchId).ifPresent(match -> {
                var paymentRequest = new PaymentRequest();
                paymentRequest.setMatchPlayer(match.getPlayerByNumber(playerNumber));
                paymentRequest.setStatus(PaymentRequestStatus.WAIT_PAYMENT);

                paymentRequest.setSum(amount);
                paymentRequest.setUserChatId(userChatId);
                paymentRequest.setCreatedDate(new Date());
                PaymentRequest savedPaymentRequest = paymentRequestRepository.save(paymentRequest);

                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(callbackQuery.getId());
                answer.setShowAlert(false);
                try {
                    accountService.pay(savedPaymentRequest);
                    answer.setText("🎉 Оплата прошла успешно! 🎉");
                    SendMessage payMsg = new SendMessage(userChatId.toString(), "Принят голос в размере " + amount + "⭐ в матче #" + match.getId() + " за игрока " + paymentRequest.getMatchPlayer().getPlayerName());
                    payMsg.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(matchService.createMatchLinkButton(match)))));
                    bot.execute(payMsg);
                } catch (PaymentException e) {
                    answer.setShowAlert(true);
                    answer.setText(String.format("Оплата не прошла. \n%s\n", e.getMessage()));
                }
                bot.execute(answer);
            });
            return true;
        } else if (query.startsWith("deposit_")) {
            String[] s = query.split("_");
            long userId = Long.parseLong(s[1]);
            stateService.setWaitingAmount(userId, StateService.State.WAITING_FOR_AMOUNT_DEP);
            SendMessage msg = new SendMessage();
            msg.setChatId(userId);
            msg.setText("Введите количество ⭐ для пополнения:");
            Integer messageId = bot.execute(msg).getMessageId();
            bot.deleteMessageScheduled(userId, messageId);
        } else if (query.startsWith("withdraw_")) {
            String[] s = query.split("_");
            long userId = Long.parseLong(s[1]);
            stateService.setWaitingAmount(userId, StateService.State.WAITING_FOR_AMOUNT_WITHDRAW);
            SendMessage msg = new SendMessage();
            msg.setChatId(userId);
            msg.setText("Введите количество ⭐ для вывода (не меньше 100):");
            Integer messageId = bot.execute(msg).getMessageId();
            bot.deleteMessageScheduled(userId, messageId);
        }else if (query.startsWith("cancelWithdraw_")) {
            String[] s = query.split("_");
            long requestId = Long.parseLong(s[1]);
            withdrawService.cancelById(userChatId, requestId, bot);
            bot.deleteMessage(userChatId, callbackQuery.getMessage().getMessageId());
        } else if (query.startsWith("userlink_")) {
            String[] s = query.split("_");
            long requestId = Long.parseLong(s[1]);
            long targetUserId = Long.parseLong(s[2]);
            String label = buildWithdrawTitle(requestId, targetUserId, null);

            List<InlineKeyboardButton> row = new ArrayList<>();
            row.add(InlineKeyboardButton.builder()
                    .text("Перейти в чат")
                    .url("tg://user?id=" + targetUserId)
                    .build());
            row.add(InlineKeyboardButton.builder()
                    .text("Списать сумму с баланса")
                    .callbackData("withdrawPay_" + requestId)
                    .build());
            row.add(InlineKeyboardButton.builder()
                    .text("Отменить вывод")
                    .callbackData("withdrawCancelAdmin_" + requestId)
                    .build());

            SendMessage sendMessage = new SendMessage(userChatId.toString(), label);
            sendMessage.setReplyMarkup(new InlineKeyboardMarkup(List.of(row)));
            bot.execute(sendMessage);
        } else if (query.startsWith("withdrawPay_")) {
            String[] s = query.split("_");
            long requestId = Long.parseLong(s[1]);
            withdrawService.markPayedByAdmin(userChatId, requestId, bot);
            bot.deleteMessage(userChatId, callbackQuery.getMessage().getMessageId());
        } else if (query.startsWith("withdrawCancelAdmin_")) {
            String[] s = query.split("_");
            long requestId = Long.parseLong(s[1]);
            withdrawService.cancelByAdmin(userChatId, requestId, bot);
            bot.deleteMessage(userChatId, callbackQuery.getMessage().getMessageId());
        } else if (query.startsWith("select_favorite_")) {
            String[] s = query.split("_");
            long userId = Long.parseLong(s[2]);
            String playerName = s[3];
            Player player = playerRepository.findByName(playerName).orElse(null);

            AnswerCallbackQuery answer = new AnswerCallbackQuery();
            answer.setCallbackQueryId(callbackQuery.getId());
            answer.setShowAlert(false);

            if (player != null) {
                BotUser user = userService.createIfNeed(userId);
                user.setFavoritePlayer(player);
                userRepository.save(user);
                answer.setText("\uD83C\uDF86 Ваш любимый смайл " + playerName + " установлен! \uD83C\uDF86");
                sendPersistentKeyboard(callbackQuery.getFrom(), userId, bot);
            } else {
                answer.setShowAlert(true);
                answer.setText("☹ Ваш любимый смайл не обнаружен в базе данных :( Попробуйте другой. ☹");
            }
            dependMessageService.deleteDependMessage(userChatId, DependMessageCode.SELECT_FAV_PLAYER, bot);
            bot.execute(answer);
        } else if (query.startsWith("replace_favorite_")) {
            String[] s = query.split("_");
            long userId = Long.parseLong(s[2]);
            String playerName = s[3];

            AnswerCallbackQuery answer = new AnswerCallbackQuery();
            answer.setCallbackQueryId(callbackQuery.getId());
            answer.setShowAlert(false);
            try {
                accountService.pay(userId, 150L);
                Player player = playerRepository.findByName(playerName).orElse(null);
                if (player != null) {
                    BotUser user = userService.createIfNeed(userId);
                    user.setFavoritePlayer(player);
                    userRepository.save(user);
                    answer.setText("\uD83C\uDF86 Ваш любимый смайл " + playerName + " установлен! \uD83C\uDF86");
                    sendPersistentKeyboard(callbackQuery.getFrom(), userId, bot);
                } else {
                    accountService.addBalance(userId, 150L);
                    answer.setText("☹ Ваш любимый смайл не обнаружен в базе данных :( Попробуйте другой. ☹");
                }
            } catch (PaymentException e) {
                answer.setShowAlert(true);
                answer.setText(String.format("Оплата не прошла. \n%s\n", e.getMessage()));
            }
            dependMessageService.deleteDependMessage(userChatId, DependMessageCode.SELECT_FAV_PLAYER, bot);
            bot.execute(answer);
        } else if (query.startsWith("account_")) {
            String[] s = query.split("_");
            long userId = Long.parseLong(s[1]);
            accountService.linkToUser(userId);
            accountService.getBalanceByUserChatId(userId);
            bot.execute(createAnswerAlert(callbackQuery, "Аккаунт создан"));
        } else if (query.startsWith("admin_")) {
            String[] s = query.split("_");
            long userId = Long.parseLong(s[1]);
            userService.setAdmin(userId);
            bot.execute(createAnswerAlert(callbackQuery, "Пользователь стал администратором"));
        } else if (query.startsWith("check_balance_")) {
            String[] s = query.split("_");
            long userId = Long.parseLong(s[2]);
            bot.execute(createAnswerAlert(callbackQuery, "Баланс пользователя:" + accountService.getBalanceByUserChatId(userId) + " ⭐"));
        } else if (query.startsWith("balance_plus_")) {
            String[] s = query.split("_");
            long userId = Long.parseLong(s[2]);

            SendMessage sendMessage = new SendMessage();
            sendMessage.enableMarkdown(true);
            sendMessage.setText("Введите сумму.\n" +
                    "Скопируйте команду ниже бота и отправьте добавив сумму:\n\n" +
                    "`plus_" + userId + "_`");

            sendMessage.setChatId(userChatId);
            Integer messageId = bot.execute(sendMessage).getMessageId();
            bot.deleteMessageScheduled(userChatId, messageId);
        } else if (query.startsWith("balance_minus_")) {
            String[] s = query.split("_");
            long userId = Long.parseLong(s[2]);

            SendMessage sendMessage = new SendMessage();
            sendMessage.enableMarkdown(true);
            sendMessage.setText("Введите сумму.\n" +
                    "Скопируйте команду ниже бота и отправьте добавив сумму:\n\n" +
                    "`minus_" + userId + "_`");
            sendMessage.setChatId(userChatId);
            Integer messageId = bot.execute(sendMessage).getMessageId();
            bot.deleteMessageScheduled(userChatId, messageId);
        }
        return false;
    }

    private AnswerCallbackQuery createAnswerAlert(CallbackQuery callbackQuery, String text) {
        AnswerCallbackQuery answer = new AnswerCallbackQuery(callbackQuery.getId());
        answer.setShowAlert(false);
        answer.setText(text);
        return answer;
    }

    protected void preCallbackQueryProcess(PreCheckoutQuery preCheckoutQuery, EmojiRaceBot bot) {
        String payload = preCheckoutQuery.getInvoicePayload();
        if (payload.startsWith("deposit_")) {
            bot.execute(new AnswerPreCheckoutQuery(preCheckoutQuery.getId(), true));
        }
    }

    public void sendPersistentKeyboard(User from, Long chatId, EmojiRaceBot bot) {
        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("💰 Баланс"));

        accountService.getByUserId(chatId);
        BotUser botUser = userService.addInfoIfNeed(from);

        Player favoritePlayer = botUser.getFavoritePlayer();
        if (favoritePlayer == null) {
            row1.add(new KeyboardButton("\uD83D\uDE0E Выбрать любимый смайл"));
        } else {
            row1.add(new KeyboardButton("\uD83D\uDE33 Сменить любимый смайл за 150 ⭐ (Текущий: " + favoritePlayer.getName() + ")"));
        }

        rows.add(row1);

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("🆘 Помощь"));
        if (userService.isAdmin(chatId)) {
            row2.add(new KeyboardButton("📤 Выводы"));
        }
        rows.add(row2);

        if (favoritePlayer != null) {
            KeyboardRow row3 = new KeyboardRow();
            row3.add(new KeyboardButton("\uD83D\uDC4A Отправить " + favoritePlayer.getName() + " в очередь за 10 ⭐"));
            rows.add(row3);
        }

        KeyboardRow row4 = new KeyboardRow();
        row4.add(new KeyboardButton("\uD83D\uDC4A Показать текущую битву"));
        rows.add(row4);


        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(rows);
        keyboard.setIsPersistent(true);
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Используйте кнопки ниже для управления ботом.");
        message.setReplyMarkup(keyboard);

        Message sentMessage = bot.execute(message);

        PinChatMessage pinMessage = new PinChatMessage();
        pinMessage.setChatId(chatId.toString());
        pinMessage.setMessageId(sentMessage.getMessageId());
        pinMessage.setDisableNotification(true);

        bot.execute(pinMessage);
    }

    private void sendCreatedWithdraws(Long adminChatId, EmojiRaceBot bot) {
        List<WithdrawRequest> createdRequests = withdrawService.getCreatedRequests();
        if (createdRequests.isEmpty()) {
            bot.execute(new SendMessage(adminChatId.toString(), "Нет активных запросов на вывод."));
            return;
        }

        StringBuilder text = new StringBuilder("Активные запросы на вывод:\n");
        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();
        for (int i = 0; i < createdRequests.size(); i++) {
            WithdrawRequest request = createdRequests.get(i);
            String line = buildWithdrawTitle(request.getId(), request.getUserChatId(), request.getSum());
            text.append(line).append("\n");
            buttons.add(List.of(InlineKeyboardButton.builder()
                    .text(line)
                    .callbackData("userlink_" + request.getId() + "_" + request.getUserChatId())
                    .build()));
        }

        SendMessage sendMessage = new SendMessage(adminChatId.toString(), text.toString());
        sendMessage.setReplyMarkup(new InlineKeyboardMarkup(buttons));
        bot.execute(sendMessage);
    }


    private String buildWithdrawTitle(Long requestId, Long userChatId, Long sum) {
        BotUser botUser = userRepository.findByUserChatId(userChatId).orElse(null);
        String username = botUser != null && botUser.getUsername() != null && !botUser.getUsername().isBlank()
                ? "@" + botUser.getUsername()
                : String.valueOf(userChatId);

        StringBuilder label = new StringBuilder("#").append(requestId).append(" ID: ").append(username);
        if (sum != null) {
            label.append(" Сумма: ").append(sum).append(" ⭐️");
        }
        return label.toString();
    }

    private ReplyKeyboard createSelectPlayerKeyboard(List<Player> players, Long chatId, boolean firstSelect) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> currentRow = new ArrayList<>();

        for (Player player : players) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(player.getName());
            String callbackData = (firstSelect ? "select_favorite_" : "replace_favorite_")
                    + chatId + "_" + player.getName();
            button.setCallbackData(callbackData);

            currentRow.add(button);
            if (currentRow.size() == 8) {
                rows.add(new ArrayList<>(currentRow));
                currentRow.clear();
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboard createDepositKeyboard(Long userId) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

        InlineKeyboardButton addAccountBtn = new InlineKeyboardButton();
        addAccountBtn.setText("Пополнить баланс звёздами");
        addAccountBtn.setCallbackData("deposit_" + userId);

        InlineKeyboardButton withdrawBtn = new InlineKeyboardButton();
        withdrawBtn.setText("Отправить запрос на вывод");
        withdrawBtn.setCallbackData("withdraw_" + userId);

        keyboard.setKeyboard(List.of(
                List.of(addAccountBtn, withdrawBtn)
        ));
        return keyboard;
    }

    public InlineKeyboardMarkup createUserRequestKeyboard(Long userId) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

        InlineKeyboardButton addAccountBtn = new InlineKeyboardButton();
        addAccountBtn.setText("Создать аккаунт");
        addAccountBtn.setCallbackData("account_" + userId);

        InlineKeyboardButton setAdminButton = new InlineKeyboardButton();
        setAdminButton.setText("Сделать админом");
        setAdminButton.setCallbackData("admin_" + userId);

        InlineKeyboardButton depositButton = new InlineKeyboardButton();
        depositButton.setText("Добавить баланса");
        depositButton.setCallbackData("balance_plus_" + userId);

        InlineKeyboardButton withdrawButton = new InlineKeyboardButton();
        withdrawButton.setText("Вывод с баланса");
        withdrawButton.setCallbackData("balance_minus_" + userId);

        InlineKeyboardButton checkBalanceButton = new InlineKeyboardButton();
        checkBalanceButton.setText("Просмотр баланса");
        checkBalanceButton.setCallbackData("check_balance_" + userId);

        keyboard.setKeyboard(List.of(
                List.of(addAccountBtn, setAdminButton),
                List.of(depositButton, withdrawButton),
                List.of(checkBalanceButton)
        ));
        return keyboard;
    }
}
