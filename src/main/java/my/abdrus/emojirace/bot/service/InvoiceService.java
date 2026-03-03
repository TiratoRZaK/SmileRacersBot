package my.abdrus.emojirace.bot.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import my.abdrus.emojirace.bot.EmojiRaceBot;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
public class InvoiceService {

    @Autowired
    private RestTemplate restTemplate;

    public Integer sendDepositInvoice(Long chatId, Long amount, EmojiRaceBot bot) throws HttpClientErrorException {
        String url = "https://api.telegram.org/bot" + bot.getBotToken() + "/sendInvoice";

        Map<String, Object> request = new HashMap<>();
        request.put("chat_id", chatId);
        request.put("title", "Пополнить баланс");
        request.put("description", "Оплатить " + amount + " ⭐");
        request.put("payload", "deposit_" + chatId + "_" + amount);
        request.put("currency", "XTR");
        request.put("provider_token", "");
        request.put("prices", List.of(
                Map.of(
                        "label", "Stars",
                        "amount", amount
                )
        ));

        var response = restTemplate.postForObject(url, request, String.class);
        if (response != null) {
            var json = new JSONObject(response);
            if (json.getBoolean("ok")) {
                return json.getJSONObject("result").getInt("message_id");
            }
        }
        return null;
    }
}
