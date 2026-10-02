package com.example.pim.domain.exception;

/** A value object was given input that can never be valid (blank key, malformed country code...). */
public class InvalidValueException extends DomainException {

    public InvalidValueException(String message) {
        super(message);
    }
}
