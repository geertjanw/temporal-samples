package com.example.stuart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StuartApplication {

    public static void main(String[] args) {
        SpringApplication.run(StuartApplication.class, args);
    }
}
