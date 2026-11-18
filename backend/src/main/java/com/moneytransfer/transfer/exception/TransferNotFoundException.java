package com.moneytransfer.transfer.exception;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException() {
        super("The requested transfer was not found.");
    }
}
