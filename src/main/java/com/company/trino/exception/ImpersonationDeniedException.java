package com.company.trino.exception;

public class ImpersonationDeniedException extends RuntimeException {

    private final String sessionUser;

    public ImpersonationDeniedException(String sessionUser) {
        super("impersonation denied for: " + sessionUser);
        this.sessionUser = sessionUser;
    }

    public String getSessionUser() { return sessionUser; }
}
