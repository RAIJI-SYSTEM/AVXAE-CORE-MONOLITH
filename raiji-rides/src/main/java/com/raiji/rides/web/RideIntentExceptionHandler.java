package com.raiji.rides.web;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RideIntentController.class)
public class RideIntentExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String > handleNotFound(
            EntityNotFoundException exception
    ){
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(exception.getMessage());
    }

    @ExceptionHandler(IllegalAccessException.class)
    public ResponseEntity<String>handleBadRequest(
            IllegalAccessException exception
    ){
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleConflict(
            IllegalStateException exception
    ){
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(exception.getMessage());
    }
}
