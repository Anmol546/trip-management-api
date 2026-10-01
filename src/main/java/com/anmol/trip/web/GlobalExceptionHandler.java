package com.anmol.trip.web;

import com.anmol.trip.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Single place where exceptions become HTTP responses.
 *
 * <p>Every error body is an RFC 9457 {@code application/problem+json} document
 * carrying a stable {@code code}, so clients can branch on the code instead of
 * parsing prose. Nothing reaches the client that would help an attacker: the
 * catch-all logs the failure and returns a generic message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final List<String> SORTABLE_PROPERTIES =
            List.of("id", "tripNumber", "source", "destination", "driverName", "status", "createdAt", "updatedAt");

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(ex.getStatus(), ex.getMessage(), request);
        problem.setProperty("code", ex.getCode());
        if (!ex.getDetails().isEmpty()) {
            problem.setProperty("details", ex.getDetails());
        }
        if (ex.getStatus().is5xxServerError()) {
            log.error("{} while handling {}", ex.getCode(), request.getRequestURI(), ex);
        } else {
            log.debug("{} while handling {}", ex.getCode(), request.getRequestURI());
        }
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    /** Bean validation on {@code @RequestBody}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException ex,
                                                               HttpServletRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "Request validation failed for " + ex.getBindingResult().getObjectName(), request);
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Bean validation on query parameters and path variables. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleParameterValidation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(
                        lastNode(violation), violation.getMessage(), String.valueOf(violation.getInvalidValue())))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request parameter validation failed", request);
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                              HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "Request body is missing or is not valid JSON matching the request schema", request);
        problem.setProperty("code", "MALFORMED_REQUEST");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        String expected = ex.getRequiredType() == null ? "the expected type" : ex.getRequiredType().getSimpleName();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "Parameter '%s' with value '%s' could not be converted to %s"
                        .formatted(ex.getName(), ex.getValue(), expected), request);
        problem.setProperty("code", "INVALID_PARAMETER");
        problem.setProperty("details", java.util.Map.of(
                "parameter", ex.getName(),
                "rejectedValue", String.valueOf(ex.getValue())));
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), request);
        problem.setProperty("code", "METHOD_NOT_ALLOWED");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(problem);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoHandler(NoHandlerFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "No endpoint " + ex.getHttpMethod()
                + " " + ex.getRequestURL(), request);
        problem.setProperty("code", "ENDPOINT_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * A constraint that no layer translated. Treated as a conflict so the caller
     * knows the request conflicted with existing data rather than the server
     * being broken.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        log.warn("Unhandled constraint violation on {}", request.getRequestURI(), ex);
        ProblemDetail problem = problem(HttpStatus.CONFLICT,
                "The request conflicts with data that already exists", request);
        problem.setProperty("code", "DATA_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrency(ConcurrencyFailureException ex,
                                                            HttpServletRequest request) {
        log.warn("Concurrency failure on {}: {}", request.getRequestURI(), ex.getClass().getSimpleName());
        ProblemDetail problem = problem(HttpStatus.CONFLICT,
                "The resource was modified concurrently. Retry with fresh state.", request);
        problem.setProperty("code", "CONCURRENT_UPDATE");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** For example sorting on a property that does not exist on Trip. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ProblemDetail> handleBadSortProperty(PropertyReferenceException ex,
                                                                HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "Sort property '%s' is not recognised. Sortable properties are %s."
                        .formatted(ex.getPropertyName(), String.join(", ", SORTABLE_PROPERTIES)), request);
        problem.setProperty("code", "INVALID_PARAMETER");
        problem.setProperty("details", Map.of("parameter", "sort", "property", ex.getPropertyName()));
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. The incident has been logged.", request);
        problem.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private ProblemDetail problem(HttpStatus status, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage(),
                error.getRejectedValue() == null ? null : String.valueOf(error.getRejectedValue()));
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot < 0 ? path : path.substring(lastDot + 1);
    }

    /**
     * One field level failure. {@code rejectedValue} is echoed because for input
     * validation it is the caller's own value, not a secret.
     */
    public record FieldViolation(String field, String message, String rejectedValue) {
    }
}
