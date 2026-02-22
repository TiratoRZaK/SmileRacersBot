package my.abdrus.smileracers.bot.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import my.abdrus.smileracers.bot.SmileRacersBot;
import my.abdrus.smileracers.bot.entity.BotUser;
import my.abdrus.smileracers.bot.entity.PaymentRequest;
import my.abdrus.smileracers.bot.entity.Player;
import my.abdrus.smileracers.bot.enumeration.PaymentRequestStatus;
import my.abdrus.smileracers.bot.exception.PaymentException;
import my.abdrus.smileracers.bot.repository.PaymentRequestRepository;
import my.abdrus.smileracers.bot.repository.PlayerRepository;
import my.abdrus.smileracers.bot.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Contact;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
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
    private MatchService matchService;
    @Autowired
    private PaymentRequestRepository paymentRequestRepository;
    @Autowired
    private AccountService accountService;
    @Autowired
    private MatchGenerationService matchGenerationService;
    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private PlayerRepository playerRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private StateService stateService;

    @Value("${telegram.bot.channel.defaultDeleteMessageMenuDelay}")
    private int defaultDeleteMessageMenuDelay;

    @Value("${telegram.bot.channel.helpLink}")
    private String helpLink;

    @Override
    public void updateProcess(Update update, SmileRacersBot bot) {
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

    private void contactProcess(Message message, Long chatId, SmileRacersBot bot) {
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
        bot.deleteMessageScheduled(message.getFrom().getId(), messageId);
    }

    private void textProcess(Message message, Long chatId, SmileRacersBot bot) {
        String text = message.getText();
        Integer messageId = message.getMessageId();
        bot.deleteMessage(chatId, messageId);

        userService.addInfoIfNeed(message.getFrom());
        accountService.linkToUser(message.getFrom().getId());

        StateService.Session session = stateService.getSession(chatId);
        if (session.state == StateService.State.WAITING_FOR_AMOUNT) {
            try {
                long amount = Long.parseLong(text);
                bot.deleteMessageScheduled(chatId, invoiceService.sendDepositInvoice(chatId, amount, bot));
                stateService.clear(chatId);
            } catch (NumberFormatException e) {
                SendMessage msg = new SendMessage();
                msg.setChatId(chatId);
                msg.setText("❌ Введите корректное число");
                bot.execute(msg);
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
            bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId(), defaultDeleteMessageMenuDelay);
        } else if (text.equals("😎 Выбрать любимый смайл")) {
            SendMessage msg = new SendMessage(chatId.toString(),
                    """
                    ❗❗❗ Внимание! ❗❗❗
                    Любимый смайл выбирается бесплатно только один раз!
                    В следующий раз смена любимого смайла будет стоить 150 ⭐
                    
                    Выбор любимого смайла открывает возможности:
                     - При ставке по линии на ваш любимый смайл, в случае его победы получите + 1 \uD83D\uDC07 подарок
                     - За 10 ⭐ ставить свой любимый смайл в очередь на участие в следующей битве
                    """);
            msg.setReplyMarkup(createSelectPlayerKeyboard(chatId, true));
            bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId(), 60_000);
        } else if (text.startsWith("\uD83D\uDE33 Сменить любимый смайл")) {
            SendMessage msg = new SendMessage(chatId.toString(),
                    """
                            Выбор любимого смайла открывает возможности:
                             - При ставке по линии на ваш любимый смайл, в случае его победы получите + 1 \uD83D\uDC07 подарок
                             - За 10 ⭐ ставить свой любимый смайл в очередь на участие в следующей битве
                    """);
            msg.setReplyMarkup(createSelectPlayerKeyboard(chatId, false));
            bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId(), 60_000);
        } else if (text.equals("🆘 Помощь")) {
            SendMessage msg = new SendMessage(chatId.toString(), "Обратитесь к владельцу канала:");
            message.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(InlineKeyboardButton.builder()
                    .text("👤 Связаться с владельцем")
                    .url(helpLink)
                    .build()))));
            bot.deleteMessageScheduled(chatId, bot.execute(msg).getMessageId(), defaultDeleteMessageMenuDelay);
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
                    matchService.sendLineByActiveMatch(chatId, false, bot),
                    defaultDeleteMessageMenuDelay);
        } else if (text.equals("/start")) {
            sendPersistentKeyboard(chatId, bot);
        }
    }

    @Override
    public boolean callbackQueryProcess(CallbackQuery callbackQuery, SmileRacersBot bot) {
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
                } catch (PaymentException e) {
                    answer.setShowAlert(true);
                    answer.setText(String.format("Оплата не прошла. \n%s\nОбратитесь к владельцу канала.", e.getMessage()));
                }
                bot.execute(answer);
            });
            return true;
        } else if (query.startsWith("deposit_")) {
            String[] s = query.split("_");
            long userId = Long.parseLong(s[1]);
            stateService.setWaitingAmount(userId);
            SendMessage msg = new SendMessage();
            msg.setChatId(userId);
            msg.setText("Введите количество ⭐ для пополнения:");
            Integer messageId = bot.execute(msg).getMessageId();
            bot.deleteMessageScheduled(userId, messageId);
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
                sendPersistentKeyboard(userId, bot);
            } else {
                answer.setShowAlert(true);
                answer.setText("☹ Ваш любимый смайл не обнаружен в базе данных :( Попробуйте другой. ☹");
            }
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
                    sendPersistentKeyboard(userId, bot);
                } else {
                    accountService.addBalance(userId, 150L);
                    answer.setText("☹ Ваш любимый смайл не обнаружен в базе данных :( Попробуйте другой. ☹");
                }
            } catch (PaymentException e) {
                answer.setShowAlert(true);
                answer.setText(String.format("Оплата не прошла. \n%s\nОбратитесь к владельцу канала.", e.getMessage()));
            }
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

    protected void preCallbackQueryProcess(PreCheckoutQuery preCheckoutQuery, SmileRacersBot bot) {
        String payload = preCheckoutQuery.getInvoicePayload();
        if (payload.startsWith("deposit_")) {
            bot.execute(new AnswerPreCheckoutQuery(preCheckoutQuery.getId(), true));
        }
    }

    public void sendPersistentKeyboard(Long chatId, SmileRacersBot bot) {
        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("💰 Баланс"));

        BotUser botUser = userService.createIfNeed(chatId);
        Player favoritePlayer = botUser.getFavoritePlayer();
        if (favoritePlayer == null) {
            row1.add(new KeyboardButton("\uD83D\uDE0E Выбрать любимый смайл"));
        } else {
            row1.add(new KeyboardButton("\uD83D\uDE33 Сменить любимый смайл за 150 ⭐ (Текущий: " + favoritePlayer.getName() + ")"));
        }

        rows.add(row1);

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("🆘 Помощь"));
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

    private ReplyKeyboard createSelectPlayerKeyboard(Long chatId, boolean firstSelect) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> currentRow = new ArrayList<>();

        List<Player> players = playerRepository.findAll();
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

        keyboard.setKeyboard(List.of(
                List.of(addAccountBtn)
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
