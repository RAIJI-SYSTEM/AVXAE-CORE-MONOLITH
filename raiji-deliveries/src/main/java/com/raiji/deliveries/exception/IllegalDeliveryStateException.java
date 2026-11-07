package com.raiji.deliveries.exception;

public class IllegalDeliveryStateException extends RuntimeException{

    public IllegalDeliveryStateException(String message){
        super(message);
    }

}
