package com.fintech.scoring.domain;

/** Una política que no se puede publicar tal como viene; el mensaje dice qué corregir. */
public class InvalidScoringPolicyException extends RuntimeException {

    public InvalidScoringPolicyException(String message) {
        super(message);
    }
}
