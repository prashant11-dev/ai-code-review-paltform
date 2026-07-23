package com.aicode.code_review_platform.review.mapper;

import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import org.springframework.stereotype.Component;

@Component
public class ReviewChunkMapper {

    public ReviewChunk toEntity(CodeReview review, CodeChunk chunk) {
        return ReviewChunk.builder()
                .review(review)
                .chunkNumber(chunk.getChunkNumber())
                .status(AppEnums.ReviewStatus.PENDING)
                .totalFiles(chunk.getFiles().size())
                .totalCharacters(chunk.getTotalCharacters())
                .build();
    }

}
