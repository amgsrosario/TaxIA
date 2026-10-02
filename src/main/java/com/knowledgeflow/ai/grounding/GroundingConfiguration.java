package com.knowledgeflow.ai.grounding;

import com.knowledgeflow.ai.grounding.scope.ScopeGateProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({GroundingProperties.class, ScopeGateProperties.class})
public class GroundingConfiguration {
}
