package com.knowledgeflow.research.source;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.knowledgeflow.research.integration.DirectModeRefusalInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(ResearchProperties.class)
public class ResearchConfiguration {

    /** Cliente real das fontes oficiais; os testes substituem-no por fixtures. */
    @Bean
    public OfficialSourceClient officialSourceClient(ResearchProperties properties) {
        return new OfficialSourceHttpClient(properties);
    }

    /** Exclusividade dos modos (§3.1): recusa da criação directa com o modo integrado ligado. */
    @Bean
    public WebMvcConfigurer directModeRefusal(ResearchProperties properties) {
        DirectModeRefusalInterceptor interceptor = new DirectModeRefusalInterceptor(properties);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns(DirectModeRefusalInterceptor.DIRECT_CREATION_PATH);
            }
        };
    }
}
