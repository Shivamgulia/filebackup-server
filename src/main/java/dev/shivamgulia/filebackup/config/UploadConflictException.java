package dev.shivamgulia.filebackup.config;

/**
 * Presigned-upload verification failed (blob missing or size mismatch); mapped to 409
 * in {@link GlobalExceptionHandler}.
 */
public class UploadConflictException extends RuntimeException {
    public UploadConflictException(String message) {
        super(message);
    }
}
