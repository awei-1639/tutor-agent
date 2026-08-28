package com.tutor.interview;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 面试状态转换前置条件，集中保证状态错误返回一致。 */
final class InterviewStateGuard {
    private InterviewStateGuard() {}

    static void requireInProgress(String status) {
        if (!"IN_PROGRESS".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "面试已结束，不能继续提交回答");
    }

    static void requireCompleted(String status) {
        if (!"COMPLETED".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "仅已完成的面试可以复测");
    }

    static void requireCancellable(String status) {
        if (!"IN_PROGRESS".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "面试已结束，不能重复收卷");
    }
}
