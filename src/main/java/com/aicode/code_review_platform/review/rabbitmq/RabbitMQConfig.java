package com.aicode.code_review_platform.review.rabbitmq;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String QUEUE = "code-review-queue";

    public static final String CHUNK_REVIEW_QUEUE = "chunk.review.queue";

    @Bean
    public Queue queue() {
        return new Queue(QUEUE);
    }

    /**
     * Dedicated queue for per-chunk AI review requests, kept separate from {@link #QUEUE} so that
     * chunk traffic and whole-review traffic can be scaled and monitored independently.
     */
    @Bean
    public Queue chunkReviewQueue() {
        return QueueBuilder.durable(CHUNK_REVIEW_QUEUE).build();
    }

    /**
     * JSON payloads instead of the default Java serialization, so DTOs such as
     * {@link com.aicode.code_review_platform.review.rabbitmq.dto.ChunkReviewMessage} can be
     * published without implementing {@link java.io.Serializable}.
     */
    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

}
