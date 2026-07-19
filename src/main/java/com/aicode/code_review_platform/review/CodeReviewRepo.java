package com.aicode.code_review_platform.review;

import com.aicode.code_review_platform.auth.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CodeReviewRepo extends JpaRepository<CodeReview,Long> {

    List<CodeReview> findByUser(User user);

}
