package com.thisnode.ext.cardimporter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CardImporterStarter {

    public static void main(String[] args) {
        SpringApplication.run(CardImporterStarter.class, args);
    }
}
