package com.vaultcore.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail business(ApiException exception, HttpServletRequest request) {
        return problem(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    ProblemDetail validation(Exception exception, HttpServletRequest request) {
        String detail = "Invalid request body, parameter, or required header";
        if (exception instanceof MethodArgumentNotValidException invalid) {
            detail = invalid.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .distinct().sorted().reduce((a, b) -> a + "; " + b).orElse(detail);
        }
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", detail, request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse framework) {
            return problem(HttpStatus.valueOf(framework.getStatusCode().value()), "HTTP_ERROR",
                    framework.getBody().getDetail(), request);
        }
        // Avoid logging request bodies, credentials, SQL parameters, or exception messages.
        LOG.error("Unhandled failure type={} requestId={}", exception.getClass().getSimpleName(),
                request.getAttribute("requestId"));
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An internal error occurred", request);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setProperty("requestId", request.getAttribute("requestId"));
        return problem;
    }
}
