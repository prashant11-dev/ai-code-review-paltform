package com.aicode.code_review_platform.review.rabbitmq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * RabbitMQ payload for a single chunk review request.
 *
 * <p>Intentionally carries only the identifier: the consumer always reloads the
 * {@link com.aicode.code_review_platform.review.ReviewChunk} from the database, which keeps
 * messages small and avoids shipping stale entity state through the broker.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkReviewMessage {

    private Long reviewChunkId;

}
