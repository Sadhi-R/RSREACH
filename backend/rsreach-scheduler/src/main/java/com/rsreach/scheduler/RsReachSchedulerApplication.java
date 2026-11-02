package com.rsreach.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.rsreach")
@EntityScan(basePackages = "com.rsreach")
@EnableJpaRepositories(basePackages = "com.rsreach")
public class RsReachSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RsReachSchedulerApplication.class, args);
    }
}
