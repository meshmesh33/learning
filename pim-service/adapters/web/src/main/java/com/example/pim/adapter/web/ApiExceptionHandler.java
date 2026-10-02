package com.example.pim.adapter.web;

import com.example.pim.application.exception.ResourceNotFoundException;
import com.example.pim.domain.exception.IllegalVersionStateException;
import com.example.pim.domain.exception.InvalidValueException;
import com.example.pim.domain.exception.StaleVersionException;
import com.example.pim.domain.exception.VariationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates domain and application errors into RFC 7807 problem responses. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler({ResourceNotFoundException.class, VariationNotFoundException.class})
    ProblemDetail notFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler({IllegalVersionStateException.class, StaleVersionException.class})
    ProblemDetail conflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(InvalidValueException.class)
    ProblemDetail badRequest(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, e);
    }

    private static ProblemDetail problem(HttpStatus status, RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }
}
