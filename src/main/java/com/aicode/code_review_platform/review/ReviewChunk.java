package com.aicode.code_review_platform.review;

import com.aicode.code_review_platform.enums.AppEnums;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "review_chunks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private CodeReview review;

    @Column(nullable = false)
    private Integer chunkNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppEnums.ReviewStatus status;

    @Column(nullable = false)
    private Integer totalFiles;

    @Column(nullable = false)
    private Integer totalCharacters;

    @Column(columnDefinition = "LONGTEXT")
    private String prompt;

    @Column(columnDefinition = "LONGTEXT")
    private String aiResponse;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private Long processingTimeMs;

}
