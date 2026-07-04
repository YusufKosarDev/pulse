package com.pulse.ingest.stream;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

@Configuration
public class RedisStreamConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamConfig.class);

    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> metricListenerContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate,
            StreamProperties properties,
            MetricStreamListener listener) {

        createConsumerGroupIfAbsent(redisTemplate, properties);

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        container.receive(
                Consumer.from(properties.group(), properties.consumer()),
                StreamOffset.create(properties.key(), ReadOffset.lastConsumed()),
                listener);

        container.start();
        log.info("Listening to stream '{}' as consumer '{}' in group '{}'",
                properties.key(), properties.consumer(), properties.group());
        return container;
    }

    private void createConsumerGroupIfAbsent(StringRedisTemplate redisTemplate, StreamProperties properties) {
        try {
            redisTemplate.opsForStream().createGroup(properties.key(), ReadOffset.from("0"), properties.group());
            log.info("Created consumer group '{}' on stream '{}'", properties.group(), properties.key());
        } catch (RedisSystemException e) {
            // BUSYGROUP: the group already exists, which is fine on restart.
            log.info("Consumer group '{}' already exists on stream '{}'", properties.group(), properties.key());
        }
    }
}
