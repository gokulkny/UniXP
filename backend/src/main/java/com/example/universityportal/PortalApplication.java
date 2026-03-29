package com.example.universityportal;

import com.example.universityportal.rag.vector.RagRetrievalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RagRetrievalProperties.class)
public class PortalApplication {
  public static void main(String[] args) {
    SpringApplication.run(PortalApplication.class, args);
  }
}

