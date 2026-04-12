package my.abdrus.emojirace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EmojiRaceBotApplication {

	public static void main(String[] args) {
		SpringApplication.run(EmojiRaceBotApplication.class, args);
	}

}
