package com.aicode.code_review_platform.review;

import com.aicode.code_review_platform.auth.User;
import com.aicode.code_review_platform.enums.AppEnums;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "code_reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    private String language;

    @Column(columnDefinition = "TEXT")
    private String code;

    @Column(columnDefinition = "TEXT")
    private String reviewResult;

    /**
     * MILESTONE 4 - headline fields of the aggregated result, kept alongside the full JSON in
     * {@link #reviewResult} so listing or sorting reviews does not require parsing every blob.
     *
     * <p>Left null when a review fails: there is no result to score, and a zero would read as a
     * review the AI actually ran and rated badly.
     */
    private Integer score;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Enumerated(EnumType.STRING)
    private AppEnums.ReviewStatus status;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * MILESTONE 4 - when the review last changed state, which in practice is when it was
     * finalized. Finalization writes it explicitly because it goes through a bulk update, and a
     * bulk update bypasses the Hibernate entity lifecycle this annotation hooks into.
     */
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    private AppEnums.ReviewSourceType sourceType;

    private String fileName;

    private String filePath;

    private Long fileSize;

    private String repositoryUrl;

}
