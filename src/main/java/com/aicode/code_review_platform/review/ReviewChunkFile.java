package com.aicode.code_review_platform.review;

import jakarta.persistence.*;
import lombok.*;

/**
 * One source file belonging to a {@link ReviewChunk}.
 *
 * <p>MILESTONE 3 - the cloned repository is deleted as soon as submission finishes, so the file
 * contents have to outlive it somewhere. Persisting them here is what lets the asynchronous
 * consumer rebuild the AI prompt from the database alone, long after the checkout is gone.
 */
@Entity
@Table(name = "review_chunk_files")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewChunkFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chunk_id", nullable = false)
    private ReviewChunk chunk;

    /**
     * Position of the file inside its chunk. Kept so the prompt is rendered in a stable order:
     * the same chunk must always produce the same prompt, otherwise reprocessing a chunk would
     * silently change the AI input.
     */
    @Column(nullable = false)
    private Integer fileOrder;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false, length = 1024)
    private String relativePath;

    private String language;

    @Column(columnDefinition = "TEXT")
    private String content;

}
