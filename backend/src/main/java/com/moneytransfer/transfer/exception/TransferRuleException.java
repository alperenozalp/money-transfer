package com.moneytransfer.transfer.exception;

public class TransferRuleException extends RuntimeException {

    private final String code;

    public TransferRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
