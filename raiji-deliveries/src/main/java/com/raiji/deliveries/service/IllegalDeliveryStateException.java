package com.raiji.deliveries.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class IllegalDeliveryStateException extends RuntimeException {

    public IllegalDeliveryStateException(String message) {
        super(message);
    }
}
