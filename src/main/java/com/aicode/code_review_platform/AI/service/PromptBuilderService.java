package com.aicode.code_review_platform.AI.service;

import com.aicode.code_review_platform.review.github.dto.CodeFile;

import java.util.List;

/**
 * Renders the AI review prompt for a set of source files.
 *
 * <p>Deliberately free of any AI knowledge: it does not call a provider, does not parse a
 * response and does not know which model will read the text. It turns files into a string, which
 * makes the prompt independently testable and gives both the synchronous review path and the
 * asynchronous chunk consumer a single definition of what a prompt looks like.
 */
public interface PromptBuilderService {

    /**
     * Builds the review prompt for the given files, in the order supplied.
     *
     * @param files files to review; must not be null or empty
     * @return the fully rendered prompt
     */
    String buildPrompt(List<CodeFile> files);

}
