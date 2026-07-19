package com.aicode.code_review_platform.enums;

public class AppEnums {

    public enum ReviewStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    public enum ReviewSourceType{
        TEXT,
        FILE,
        GITHUB
    }

}
