package com.rsreach.worker.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfig {

    @Bean(name = "workerTaskExecutor")
    ThreadPoolTaskExecutor workerTaskExecutor(WorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getConcurrency());
        executor.setMaxPoolSize(properties.getConcurrency());
        executor.setThreadNamePrefix("rsreach-worker-");
        executor.initialize();
        return executor;
    }
}
