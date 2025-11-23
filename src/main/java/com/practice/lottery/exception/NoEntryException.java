package com.practice.lottery.exception;

public class NoEntryException extends RuntimeException {
    public NoEntryException(String message) {
        super(message);
    }

    public NoEntryException(String message, Throwable cause) {
        super(message, cause);
    }
}