package com.mentalcare.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Makes sure the data directory exists before the SQLite datasource opens the database file.
 * Runs as a BeanFactoryPostProcessor so it happens before any ordinary bean is created.
 */
@Configuration
public class DataDirectoryConfig {

    @Bean
    static BeanFactoryPostProcessor dataDirectoryInitializer(Environment env) {
        return beanFactory -> {
            String dataDir = env.getProperty("mentalcare.data-dir", "../data");
            String markdownDir = env.getProperty("mentalcare.markdown-dir", dataDir + "/sessions");
            try {
                Files.createDirectories(Path.of(dataDir));
                Files.createDirectories(Path.of(markdownDir));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create data directory: " + dataDir, e);
            }
        };
    }
}
