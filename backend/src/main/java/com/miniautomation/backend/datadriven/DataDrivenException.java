package com.miniautomation.backend.datadriven;

/**
 * Thrown when data-driven configuration is invalid — bad file, missing mapping,
 * invalid step range, etc.  Always results in a 400 Bad Request via the
 * global exception handler rather than a 500 Internal Server Error.
 */
public class DataDrivenException extends RuntimeException {

    public DataDrivenException(String message) {
        super(message);
    }

    public DataDrivenException(String message, Throwable cause) {
        super(message, cause);
    }
}
