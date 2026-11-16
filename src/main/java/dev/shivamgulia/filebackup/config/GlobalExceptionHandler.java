package dev.shivamgulia.filebackup.config;

import dev.shivamgulia.filebackup.user.AdminUserController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps exceptions to clean JSON error responses instead of Spring's default whitelabel JSON.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(int status, String message, Map<String, String> fieldErrors) {
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ErrorResponse notFound(NotFoundException ex) {
        return new ErrorResponse(404, ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse invalidRequest(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> errors.put(e.getField(), e.getDefaultMessage()));
        return new ErrorResponse(400, "Validation failed", errors);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse conflict(DataIntegrityViolationException ex) {
        return new ErrorResponse(409, "Data integrity violation (duplicate key or invalid reference)", null);
    }

    @ExceptionHandler(UploadConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse uploadConflict(UploadConflictException ex) {
        return new ErrorResponse(409, ex.getMessage(), null);
    }

    @ExceptionHandler(AdminUserController.DuplicateUserException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse duplicateUser(AdminUserController.DuplicateUserException ex) {
        return new ErrorResponse(409, ex.getMessage(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse badRequest(IllegalArgumentException ex) {
        return new ErrorResponse(400, ex.getMessage(), null);
    }
}
