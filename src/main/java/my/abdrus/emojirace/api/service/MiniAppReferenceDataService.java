package my.abdrus.emojirace.api.service;

import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import my.abdrus.emojirace.bot.entity.Player;
import my.abdrus.emojirace.bot.repository.PlayerRepository;
import my.abdrus.emojirace.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MiniAppReferenceDataService {

    private final PlayerRepository playerRepository;

    @Cacheable(CacheConfig.PLAYERS_EMOJI_CACHE)
    public List<String> getAllEmojis() {
        return playerRepository.findAll().stream()
                .map(Player::getName)
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
