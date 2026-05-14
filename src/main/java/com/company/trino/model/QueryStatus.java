package com.company.trino.model;

/** 비동기 쿼리 잡의 실행 상태. */
public enum QueryStatus {
    RUNNING,
    FINISHED,
    FAILED
}
