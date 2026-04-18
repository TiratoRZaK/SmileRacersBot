package my.abdrus.emojirace.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class CacheConfig {

    public static final String PLAYERS_EMOJI_CACHE = "miniapp.players.emojis";
    public static final String LEADERBOARDS_CACHE = "miniapp.leaderboards";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCache emojiCache = new CaffeineCache(
                PLAYERS_EMOJI_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(30))
                        .maximumSize(1_000)
                        .build()
        );

        CaffeineCache leaderboardsCache = new CaffeineCache(
                LEADERBOARDS_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(15))
                        .maximumSize(100)
                        .build()
        );

        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(emojiCache, leaderboardsCache));
        return cacheManager;
    }
}
