package dev.shivamgulia.filebackup.config;

/**
 * Thrown by controllers/services when a resource does not exist; mapped to 404 in {@link GlobalExceptionHandler}.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
