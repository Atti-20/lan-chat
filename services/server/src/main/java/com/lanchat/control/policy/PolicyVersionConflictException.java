package com.lanchat.control.policy;

public class PolicyVersionConflictException extends RuntimeException {
    public PolicyVersionConflictException(String message) {
        super(message);
    }
}
