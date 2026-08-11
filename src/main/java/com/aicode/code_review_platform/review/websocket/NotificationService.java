package com.aicode.code_review_platform.review.websocket;

import com.aicode.code_review_platform.review.dto.ReviewNotification;
import com.aicode.code_review_platform.enums.AppEnums;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    @Autowired
    private  SimpMessagingTemplate messagingTemplate;

    public void notifyReviewCompleted(
            Long reviewId
    ) {

        send(
                reviewId,
                AppEnums.ReviewStatus.COMPLETED,
                "Review completed"
        );
    }

    /**
     * MILESTONE 4 - a review whose chunks all failed still has to reach the client, otherwise it
     * waits forever on a notification that will never arrive.
     *
     * <p>Same destination and same payload as the success case; only the status differs, so an
     * existing subscriber needs no changes to receive it.
     */
    public void notifyReviewFailed(
            Long reviewId
    ) {

        send(
                reviewId,
                AppEnums.ReviewStatus.FAILED,
                "Review failed"
        );
    }

    private void send(
            Long reviewId,
            AppEnums.ReviewStatus status,
            String message
    ) {

        messagingTemplate.convertAndSend(
                "/topic/reviews/" + reviewId,
                ReviewNotification.builder()
                        .reviewId(reviewId)
                        .status(status)
                        .message(message)
                        .build()
        );
    }

}
