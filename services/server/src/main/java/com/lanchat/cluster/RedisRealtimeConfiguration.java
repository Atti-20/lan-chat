package com.lanchat.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Starts the Redis subscription only when cluster routing is explicitly enabled. */
@Configuration
public class RedisRealtimeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RedisRealtimeConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "lanchat.cluster", name = "enabled", havingValue = "true")
    RedisMessageListenerContainer lanChatRealtimeListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisRealtimeRouter router) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setRecoveryInterval(5_000);
        container.setErrorHandler(exception ->
                log.warn("Redis 跨实例订阅暂时不可用，将自动重试: {}", exception.getMessage()));
        container.addMessageListener(
                (message, pattern) -> router.accept(message.getBody()),
                new ChannelTopic(router.channel())
        );
        return container;
    }
}
