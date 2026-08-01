package com.aicode.code_review_platform.review.rabbitmq;

import com.aicode.code_review_platform.review.chunk.service.ChunkProcessingService;
import com.aicode.code_review_platform.review.rabbitmq.dto.ChunkReviewMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * MILESTONE 3 - the missing consumer of {@link RabbitMQConfig#CHUNK_REVIEW_QUEUE}.
 *
 * <p>Pure transport adapter: it unwraps the chunk id and hands it to
 * {@link ChunkProcessingService}. No database access, no prompt building, no AI calls - keeping
 * this class free of them is what lets the whole pipeline be tested without a broker.
 *
 * <p>Exceptions are allowed to propagate so the broker sees the failure rather than a silent ack.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChunkReviewConsumer {

    private final ChunkProcessingService chunkProcessingService;

    @RabbitListener(queues = RabbitMQConfig.CHUNK_REVIEW_QUEUE)
    public void consume(ChunkReviewMessage message) {

        // A payload with no id can never succeed, and rejecting it would only put it back in
        // front of the queue. Drop it here rather than letting it NPE deeper in the stack.
        if (message == null || message.getReviewChunkId() == null) {
            log.error("Discarding malformed ChunkReviewMessage with no chunk id: {}", message);
            return;
        }

        Long reviewChunkId = message.getReviewChunkId();

        log.info("Received chunk {}", reviewChunkId);

        chunkProcessingService.process(reviewChunkId);
    }

}
