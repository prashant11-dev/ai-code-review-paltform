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

    /**
     * PHASE 7 - hands one persisted chunk to the broker.
     */
    @Override
    public void publish(ReviewChunk reviewChunk) {

        // Id only. The row is already committed, so the consumer reloads it from the database
        // instead of trusting entity state that may be stale by the time the message is delivered.
        ChunkReviewMessage message = ChunkReviewMessage.builder()
                .reviewChunkId(reviewChunk.getId())
                .build();

        try {
            // Sent to the default exchange with the queue name as the routing key, so it lands
            // directly on chunk.review.queue.
            rabbitTemplate.convertAndSend(RabbitMQConfig.CHUNK_REVIEW_QUEUE, message);

            log.info(
                    "Published ReviewChunk {} to queue {}",
                    reviewChunk.getId(),
                    RabbitMQConfig.CHUNK_REVIEW_QUEUE
            );

        } catch (AmqpException e) {
            // Publishing failed, so this chunk will never be reviewed. Surface it rather than
            // swallowing it - the orchestrator needs to fail the whole submission.
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
