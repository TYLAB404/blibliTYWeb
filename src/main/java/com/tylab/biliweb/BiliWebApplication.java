package com.tylab.biliweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BiliWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BiliWebApplication.class, args);
    }
}
