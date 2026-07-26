package com.aicode.code_review_platform.review.rabbitmq;

import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.rabbitmq.dto.ChunkReviewMessage;
import com.aicode.code_review_platform.review.rabbitmq.exception.ChunkPublishException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkProducerServiceImpl implements ChunkProducerService {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(ReviewChunk reviewChunk) {

        ChunkReviewMessage message = ChunkReviewMessage.builder()
                .reviewChunkId(reviewChunk.getId())
                .build();

        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.CHUNK_REVIEW_QUEUE, message);

            log.info(
                    "Published ReviewChunk {} to queue {}",
                    reviewChunk.getId(),
                    RabbitMQConfig.CHUNK_REVIEW_QUEUE
            );

        } catch (AmqpException e) {
            log.error(
                    "Failed to publish ReviewChunk {} to queue {}: {}",
                    reviewChunk.getId(),
                    RabbitMQConfig.CHUNK_REVIEW_QUEUE,
                    e.getMessage(),
                    e
            );

            throw new ChunkPublishException(
                    "Failed to publish ReviewChunk " + reviewChunk.getId() + " for processing",
                    e
            );
        }
    }

}
