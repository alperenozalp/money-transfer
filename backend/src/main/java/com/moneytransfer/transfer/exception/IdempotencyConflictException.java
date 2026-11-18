package com.moneytransfer.transfer.exception;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("The idempotency key was already used for a different request.");
    }
}
