package com.k8s.agent.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AlertBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AlertBackendApplication.class, args);
	}

}
