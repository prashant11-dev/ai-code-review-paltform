package com.aicode.code_review_platform.AI.dto.gemini;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Content {

    private List<Part> parts;
}