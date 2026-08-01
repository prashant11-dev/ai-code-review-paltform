package com.aicode.code_review_platform.review.mapper;

import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.ReviewChunkFile;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import com.aicode.code_review_platform.review.github.dto.CodeFile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

@Component
public class ReviewChunkMapper {

    /**
     * PHASE 6 (mapping step) - converts a transient CodeChunk into the persistable ReviewChunk row.
     *
     * <p>The row itself carries only metadata; the file contents go into separate
     * {@link ReviewChunkFile} rows via {@link #toFileEntities(ReviewChunk, CodeChunk)}. The prompt
     * is left empty on purpose - MILESTONE 3 renders it in the consumer, immediately before the
     * AI call, from the persisted files.
     */
    public ReviewChunk toEntity(CodeReview review, CodeChunk chunk) {
        return ReviewChunk.builder()
                .review(review)
                .chunkNumber(chunk.getChunkNumber())
                .status(AppEnums.ReviewStatus.PENDING)
                .totalFiles(chunk.getFiles().size())
                .totalCharacters(chunk.getTotalCharacters())
                .build();
    }

    /**
     * MILESTONE 3 - snapshots the chunk's files so they survive deletion of the cloned repository.
     *
     * <p>The list index becomes {@code fileOrder}, which is what makes the prompt reproducible:
     * the consumer reads the files back in exactly the order they were packed.
     */
    public List<ReviewChunkFile> toFileEntities(ReviewChunk reviewChunk, CodeChunk chunk) {

        List<CodeFile> files = chunk.getFiles();

        return IntStream.range(0, files.size())
                .mapToObj(index -> toFileEntity(reviewChunk, files.get(index), index))
                .toList();
    }

    /**
     * Consumer side: turns a persisted file back into the in-memory shape the prompt builder and
     * the AI layer already understand, so nothing downstream needs to know about the entity.
     */
    public CodeFile toCodeFile(ReviewChunkFile file) {
        return CodeFile.builder()
                .fileName(file.getFileName())
                .relativePath(file.getRelativePath())
                .language(file.getLanguage())
                .content(file.getContent())
                .build();
    }

    private ReviewChunkFile toFileEntity(ReviewChunk reviewChunk, CodeFile file, int index) {
        return ReviewChunkFile.builder()
                .chunk(reviewChunk)
                .fileOrder(index)
                .fileName(file.getFileName())
                .relativePath(file.getRelativePath() != null ? file.getRelativePath() : file.getFileName())
                .language(file.getLanguage())
                .content(file.getContent())
                .build();
    }

}
