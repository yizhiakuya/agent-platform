package com.agentplatform.agent.config;

import com.agentplatform.agent.chat.MediaGalleryCache;
import com.agentplatform.agent.chat.NoopMediaGalleryCache;
import com.agentplatform.agent.chat.RedisMediaGalleryCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class MediaGalleryCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(MediaGalleryCacheConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "agent-platform.agent.media-gallery-cache", name = "enabled", havingValue = "true")
    public LettuceConnectionFactory mediaGalleryRedisConnectionFactory(AgentProperties properties) {
        AgentProperties.MediaGalleryCache cache = properties.agent().mediaGalleryCache();
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(cache.redisHost(), cache.redisPort());
        if (cache.redisUsername() != null && !cache.redisUsername().isBlank()) {
            config.setUsername(cache.redisUsername());
        }
        if (cache.redisPassword() != null && !cache.redisPassword().isBlank()) {
            config.setPassword(cache.redisPassword());
        }
        config.setDatabase(cache.redisDatabase());
        return new LettuceConnectionFactory(config);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent-platform.agent.media-gallery-cache", name = "enabled", havingValue = "true")
    public StringRedisTemplate mediaGalleryStringRedisTemplate(LettuceConnectionFactory mediaGalleryRedisConnectionFactory) {
        return new StringRedisTemplate(mediaGalleryRedisConnectionFactory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent-platform.agent.media-gallery-cache", name = "enabled", havingValue = "true")
    public RedisTemplate<String, byte[]> mediaGalleryBytesRedisTemplate(LettuceConnectionFactory mediaGalleryRedisConnectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(mediaGalleryRedisConnectionFactory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent-platform.agent.media-gallery-cache", name = "enabled", havingValue = "true")
    public MediaGalleryCache redisMediaGalleryCache(StringRedisTemplate mediaGalleryStringRedisTemplate,
                                                    RedisTemplate<String, byte[]> mediaGalleryBytesRedisTemplate,
                                                    ObjectMapper mapper,
                                                    AgentProperties properties) {
        AgentProperties.MediaGalleryCache cache = properties.agent().mediaGalleryCache();
        log.info("Media gallery Redis cache enabled: {}:{} db={}",
                cache.redisHost(), cache.redisPort(), cache.redisDatabase());
        return new RedisMediaGalleryCache(mediaGalleryStringRedisTemplate, mediaGalleryBytesRedisTemplate, mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent-platform.agent.media-gallery-cache", name = "enabled", havingValue = "false", matchIfMissing = true)
    public MediaGalleryCache noopMediaGalleryCache() {
        log.info("Media gallery Redis cache disabled; device calls will not use shared server-side cache");
        return new NoopMediaGalleryCache();
    }
}
