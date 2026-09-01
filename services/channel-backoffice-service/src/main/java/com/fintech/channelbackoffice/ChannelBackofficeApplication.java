package com.fintech.channelbackoffice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class ChannelBackofficeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChannelBackofficeApplication.class, args);
    }
}
