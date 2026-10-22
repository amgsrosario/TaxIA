package com.knowledgeflow.research.source;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ResearchProperties.class)
public class ResearchConfiguration {

    /** Cliente real das fontes oficiais; os testes substituem-no por fixtures. */
    @Bean
    public OfficialSourceClient officialSourceClient(ResearchProperties properties) {
        return new OfficialSourceHttpClient(properties);
    }
}
