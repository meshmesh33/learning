package com.example.pim.domain.exception;

/** Base type for violations of a business rule. Adapters map subclasses to protocol-specific errors. */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
