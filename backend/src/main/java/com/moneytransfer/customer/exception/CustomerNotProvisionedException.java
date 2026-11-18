package com.moneytransfer.customer.exception;

public class CustomerNotProvisionedException extends RuntimeException {

    public CustomerNotProvisionedException() {
        super("Authenticated customer is not provisioned.");
    }
}
