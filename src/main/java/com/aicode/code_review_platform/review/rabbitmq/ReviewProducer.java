package com.aicode.code_review_platform.review.rabbitmq;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReviewProducer {

    @Autowired
    private  RabbitTemplate rabbitTemplate;

    public void sendReview(Long reviewId) {

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.QUEUE,
                reviewId
        );
    }
}
