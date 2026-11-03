package com.yottaflex.reliability.generation.api;

import java.time.Instant;

import com.yottaflex.reliability.generation.application.GenerationConflictException;
import com.yottaflex.reliability.generation.application.GenerationRunNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice(basePackageClasses = GenerationRunController.class)
public class GenerationApiExceptionHandler {

    @ExceptionHandler(GenerationRunNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(GenerationRunNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(Instant.now(), "GENERATION_RUN_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler({GenerationConflictException.class, IllegalStateException.class})
    public ResponseEntity<ApiError> handleConflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(Instant.now(), "GENERATION_CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception exception) {
        String message = exception instanceof MethodArgumentNotValidException validationException
                ? validationException.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .findFirst().orElse("Invalid request")
                : exception.getMessage();
        return ResponseEntity.badRequest().body(new ApiError(Instant.now(), "INVALID_GENERATION_REQUEST", message));
    }

    public record ApiError(Instant timestamp, String code, String message) {
    }
}
