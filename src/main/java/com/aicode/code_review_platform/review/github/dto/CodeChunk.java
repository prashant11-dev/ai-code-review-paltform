package com.aicode.code_review_platform.review.github.dto;

import lombok.*;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CodeChunk {

    private Integer chunkNumber;

    private List<CodeFile> files;

    private Integer totalCharacters;
}
