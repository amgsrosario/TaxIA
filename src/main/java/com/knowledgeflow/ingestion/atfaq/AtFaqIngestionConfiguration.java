package com.knowledgeflow.ingestion.atfaq;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AtFaqProperties.class)
public class AtFaqIngestionConfiguration {
}
