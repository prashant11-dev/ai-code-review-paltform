package com.aicode.code_review_platform.review.rabbitmq;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String QUEUE = "code-review-queue";

    @Bean
    public Queue queue() {
        return new Queue(QUEUE);
    }

}
