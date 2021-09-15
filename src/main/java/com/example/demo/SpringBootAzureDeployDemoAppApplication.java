package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class SpringBootAzureDeployDemoAppApplication {

	@GetMapping("/message")
	public String getMessage() {
		return "Connected - App deployed in Azure / GitHub / Spring boot";
	}
	public static void main(String[] args) {
		SpringApplication.run(SpringBootAzureDeployDemoAppApplication.class, args);
	}

}
