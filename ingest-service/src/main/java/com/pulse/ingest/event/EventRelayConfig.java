package com.pulse.ingest.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class EventRelayConfig {

    @Bean
    public RedisMessageListenerContainer eventRelayContainer(
            RedisConnectionFactory connectionFactory,
            RedisEventRelay relay,
            @Value("${pulse.events.channel}") String channel) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(relay, new ChannelTopic(channel));
        return container;
    }
}
