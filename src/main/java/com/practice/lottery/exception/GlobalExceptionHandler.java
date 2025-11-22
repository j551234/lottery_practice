package com.practice.lottery.exception;

import com.practice.lottery.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException ex,
            WebRequest request
    ) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            fieldErrors.put(fieldName, errorMessage);
        });

        ApiResponse.ErrorDetails errorDetails = ApiResponse.ErrorDetails.builder()
                .type("VALIDATION_ERROR")
                .detail("Request validation failed")
                .fieldErrors(fieldErrors)
                .build();

        ApiResponse<Void> response = ApiResponse.error(
                400,
                "Validation failed",
                errorDetails
        );
        response.setPath(request.getDescription(false).replace("uri=", ""));

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handle authentication exceptions
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(
            AuthenticationException ex,
            WebRequest request
    ) {
        log.warn("Authentication failed: {}", ex.getMessage());

        ApiResponse.ErrorDetails errorDetails = ApiResponse.ErrorDetails.builder()
                .type("AUTHENTICATION_ERROR")
                .detail(ex.getMessage())
                .build();

        ApiResponse<Void> response = ApiResponse.error(
                401,
                "Authentication failed",
                errorDetails
        );
        response.setPath(request.getDescription(false).replace("uri=", ""));

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * Handle lottery specific exceptions
     */
    @ExceptionHandler(LotteryException.class)
    public ResponseEntity<ApiResponse<Void>> handleLotteryException(
            LotteryException ex,
            WebRequest request
    ) {
        log.error("Lottery exception occurred: {}", ex.getMessage());

        ApiResponse.ErrorDetails errorDetails = ApiResponse.ErrorDetails.builder()
                .type("LOTTERY_ERROR")
                .detail(ex.getMessage())
                .build();

        ApiResponse<Void> response = ApiResponse.error(
                400,
                ex.getMessage(),
                errorDetails
        );
        response.setPath(request.getDescription(false).replace("uri=", ""));

        return ResponseEntity.badRequest().body(response);
    }



    /**
     * Handle all other exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGlobalException(
            Exception ex,
            WebRequest request
    ) {
        log.error("Unexpected error occurred", ex);

        ApiResponse.ErrorDetails errorDetails = ApiResponse.ErrorDetails.builder()
                .type("INTERNAL_ERROR")
                .detail("An unexpected error occurred")
                .build();

        ApiResponse<Void> response = ApiResponse.error(
                500,
                "Internal server error",
                errorDetails
        );
        response.setPath(request.getDescription(false).replace("uri=", ""));

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}