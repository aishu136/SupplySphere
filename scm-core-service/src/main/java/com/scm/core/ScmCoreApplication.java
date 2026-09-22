package com.scm.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ScmCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScmCoreApplication.class, args);
    }
}
