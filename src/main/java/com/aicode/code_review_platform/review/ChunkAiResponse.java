package com.aicode.code_review_platform.review;

/**
 * MILESTONE 4 - the two columns aggregation actually needs from a completed
 * {@link ReviewChunk}.
 *
 * <p>Projected rather than loading whole entities because a chunk also carries its rendered
 * prompt, which can run to tens of thousands of characters. A review with hundreds of chunks
 * would otherwise pull all of that into memory just to read the responses next to it.
 *
 * @param chunkNumber position of the chunk inside its review, used to report which chunk a
 *                    malformed response came from
 * @param aiResponse  the serialized {@link com.aicode.code_review_platform.AI.dto.AIReviewResult}
 *                    stored when the chunk completed
 */
public record ChunkAiResponse(Integer chunkNumber, String aiResponse) {
}
