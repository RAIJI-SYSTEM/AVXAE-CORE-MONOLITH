package com.raiji;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RaijiApplication {

    public static void main(String[] args) {
        SpringApplication.run(RaijiApplication.class, args);
    }
}
