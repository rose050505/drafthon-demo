package com.v2v.backend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class Api {
    static ResponseStatusException error(HttpStatus status, String detail) {
        return new ResponseStatusException(status, detail);
    }

    static ResponseStatusException missing() {
        return error(HttpStatus.NOT_FOUND, "요청한 항목을 찾을 수 없습니다.");
    }

    static ResponseStatusException conflict(String detail) {
        return error(HttpStatus.CONFLICT, detail);
    }

    static ResponseStatusException invalid(String detail) {
        return error(HttpStatus.BAD_REQUEST, detail);
    }

    static <T> T first(List<T> rows) {
        return rows.stream().findFirst().orElseThrow(Api::missing);
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static int limit(int value) {
        if (value < 1 || value > 100) throw invalid("limit은 1~100이어야 합니다.");
        return value;
    }

    static int offset(int value) {
        if (value < 0) throw invalid("offset은 0 이상이어야 합니다.");
        return value;
    }

    static void integrationToken(String configured, String presented) {
        if (configured == null || configured.isBlank()) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "연동 토큰이 설정되지 않았습니다.");
        }
        if (presented == null || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8))) {
            throw error(HttpStatus.UNAUTHORIZED, "연동 토큰을 확인하세요.");
        }
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "이미 등록된 항목입니다.");
    }
}
