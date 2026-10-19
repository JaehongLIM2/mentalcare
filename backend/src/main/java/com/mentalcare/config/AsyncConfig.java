package com.mentalcare.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    /** Runs streaming counseling turns off the servlet thread. Virtual threads: one per stream. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService streamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
