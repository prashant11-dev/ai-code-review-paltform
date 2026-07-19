package com.aicode.code_review_platform.AI.dto;

import com.aicode.code_review_platform.review.github.dto.CodeFile;
import lombok.*;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReviewContext {

    private List<CodeFile> files;

}
