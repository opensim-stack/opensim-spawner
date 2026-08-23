package uk.co.bithatch.opensim.spawner.service;

public class ExternalDependencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExternalDependencyException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExternalDependencyException(String message) {
        super(message);
    }
}
