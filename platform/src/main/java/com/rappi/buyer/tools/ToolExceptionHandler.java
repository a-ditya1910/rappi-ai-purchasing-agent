package com.rappi.buyer.tools;

import com.rappi.buyer.tools.ToolController.NotFound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Turns exceptions into the same {ok:false, error, detail} envelope every tool
 * uses, so the agent never has to parse a stack trace to work out what it did
 * wrong.
 *
 * Everything returns 200 on purpose. The http status is about the transport;
 * whether the tool call worked is in the body. A 500 makes an http client retry
 * or give up, when what we want is for the model to read the message and fix
 * its arguments.
 */
@RestControllerAdvice
public class ToolExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolExceptionHandler.class);

    @ExceptionHandler(NotFound.class)
    public ResponseEntity<ToolResponse<Void>> notFound(NotFound e) {
        return ResponseEntity.ok(ToolResponse.error(e.code, e.getMessage(), e.suggestions));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ToolResponse<Void>> invalid(MethodArgumentNotValidException e) {
        List<String> problems = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .toList();
        return ResponseEntity.ok(ToolResponse.error("INVALID_ARGUMENTS",
                String.join("; ", problems), problems));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ToolResponse<Void>> badArgument(IllegalArgumentException e) {
        return ResponseEntity.ok(ToolResponse.error("BAD_ARGUMENT", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ToolResponse<Void>> notPossible(IllegalStateException e) {
        return ResponseEntity.ok(ToolResponse.error("CANNOT_COMPUTE", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ToolResponse<Void>> unexpected(Exception e) {
        log.error("unhandled error in a tool call", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ToolResponse.error("INTERNAL_ERROR", e.getClass().getSimpleName()));
    }
}
