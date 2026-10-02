package com.octant.common;

public class ContractViolationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public ContractViolationException(String message) {
        super(message);
    }
}
