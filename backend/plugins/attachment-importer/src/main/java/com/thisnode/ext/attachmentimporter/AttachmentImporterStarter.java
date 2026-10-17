package com.thisnode.ext.attachmentimporter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AttachmentImporterStarter {

    public static void main(String[] args) {
        SpringApplication.run(AttachmentImporterStarter.class, args);
    }
}
