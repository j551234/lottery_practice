package com.practice.lottery.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /**
     * Response status code (200, 400, 500, etc.)
     */
    private Integer code;

    /**
     * Response message
     */
    private String message;

    /**
     * Response data payload
     */
    private T data;

    /**
     * Timestamp of the response
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /**
     * Request path (optional, useful for debugging)
     */
    private String path;

    /**
     * Error details (only present in error responses)
     */
    private ErrorDetails error;

    // ========== Static Factory Methods ==========

    /**
     * Success response with data
     */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message("Success")
                .data(data)
                .build();
    }

    /**
     * Success response with custom message
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message(message)
                .data(data)
                .build();
    }

    /**
     * Success response without data
     */
    public static <T> ApiResponse<T> success() {
        return ApiResponse.<T>builder()
                .code(200)
                .message("Success")
                .build();
    }

    /**
     * Created response (201)
     */
    public static <T> ApiResponse<T> created(T data) {
        return ApiResponse.<T>builder()
                .code(201)
                .message("Created")
                .data(data)
                .build();
    }

    /**
     * No content response (204)
     */
    public static <T> ApiResponse<T> noContent() {
        return ApiResponse.<T>builder()
                .code(204)
                .message("No Content")
                .build();
    }

    /**
     * Bad request response (400)
     */
    public static <T> ApiResponse<T> badRequest(String message) {
        return ApiResponse.<T>builder()
                .code(400)
                .message(message)
                .build();
    }

    /**
     * Unauthorized response (401)
     */
    public static <T> ApiResponse<T> unauthorized(String message) {
        return ApiResponse.<T>builder()
                .code(401)
                .message(message)
                .build();
    }

    /**
     * Forbidden response (403)
     */
    public static <T> ApiResponse<T> forbidden(String message) {
        return ApiResponse.<T>builder()
                .code(403)
                .message(message)
                .build();
    }

    /**
     * Not found response (404)
     */
    public static <T> ApiResponse<T> notFound(String message) {
        return ApiResponse.<T>builder()
                .code(404)
                .message(message)
                .build();
    }

    /**
     * Internal server error response (500)
     */
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .code(500)
                .message(message)
                .build();
    }

    /**
     * Custom error response with error details
     */
    public static <T> ApiResponse<T> error(Integer code, String message, ErrorDetails errorDetails) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .error(errorDetails)
                .build();
    }

    // ========== Error Details Inner Class ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetails {
        /**
         * Error type/category
         */
        private String type;

        /**
         * Detailed error message
         */
        private String detail;

        /**
         * Field-level validation errors
         */
        private java.util.Map<String, String> fieldErrors;

        /**
         * Stack trace (only in development)
         */
        private String stackTrace;
    }
}
