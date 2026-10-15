package com.thisnode.ext.baidusync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableAsync(proxyTargetClass = true)
@SpringBootApplication
public class BaiduSyncStarter {

    public static void main(String[] args) {
        SpringApplication.run(BaiduSyncStarter.class, args);
    }
}
