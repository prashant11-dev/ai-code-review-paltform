package com.aicode.code_review_platform.review.mapper;

import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import org.springframework.stereotype.Component;

@Component
public class ReviewChunkMapper {

    /**
     * PHASE 6 (mapping step) - converts a transient CodeChunk into the persistable ReviewChunk row.
     *
     * <p>Note what is <em>not</em> copied across: the file contents. They live on only inside the
     * pre-rendered prompt, which is why the prompt has to be passed in here rather than built later.
     * totalFiles and totalCharacters are kept purely as reporting metadata.
     */
    public ReviewChunk toEntity(CodeReview review, CodeChunk chunk, String prompt) {
        return ReviewChunk.builder()
                .review(review)
                .chunkNumber(chunk.getChunkNumber())
                .status(AppEnums.ReviewStatus.PENDING)
                .totalFiles(chunk.getFiles().size())
                .totalCharacters(chunk.getTotalCharacters())
                .prompt(prompt)
                .build();
    }

}
