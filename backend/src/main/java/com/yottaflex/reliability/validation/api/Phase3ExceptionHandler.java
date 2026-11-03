package com.yottaflex.reliability.validation.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes=Phase3Controller.class)
public class Phase3ExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> badRequest(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("timestamp",Instant.now(),"code","INVALID_PHASE3_REQUEST","message",safe(e)));}
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,Object>> conflict(IllegalStateException e){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("timestamp",Instant.now(),"code","PHASE3_STATE_CONFLICT","message",safe(e)));}
    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String,Object>> notFound(EmptyResultDataAccessException e){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("timestamp",Instant.now(),"code","PHASE3_RECORD_NOT_FOUND","message","The requested Phase 3 record was not found."));}
    private String safe(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
