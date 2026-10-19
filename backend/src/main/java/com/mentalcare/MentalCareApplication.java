package com.mentalcare;

import com.mentalcare.config.AppProperties;
import com.mentalcare.config.OpenAiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, OpenAiProperties.class})
public class MentalCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(MentalCareApplication.class, args);
    }
}
