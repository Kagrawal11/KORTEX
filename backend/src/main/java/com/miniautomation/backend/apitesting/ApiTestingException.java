package com.miniautomation.backend.apitesting;

/** Thrown for invalid API Testing configuration or requests — handled by GlobalExceptionHandler as a 400. */
public class ApiTestingException extends RuntimeException {
    public ApiTestingException(String message) {
        super(message);
    }

    public ApiTestingException(String message, Throwable cause) {
        super(message, cause);
    }
}
