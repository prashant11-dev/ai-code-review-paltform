package com.aicode.code_review_platform.review;

import com.aicode.code_review_platform.auth.User;
import com.aicode.code_review_platform.enums.AppEnums;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

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

    @Enumerated(EnumType.STRING)
    private AppEnums.ReviewStatus status;

    @CreationTimestamp
    private LocalDateTime createdAt;

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
