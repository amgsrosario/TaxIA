package com.knowledgeflow.ai.grounding.scope;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuração do gate de contradição de âmbito (M4-SCOPE). Activo por omissão. */
@ConfigurationProperties(prefix = "knowledgeflow.grounding.scope-gate")
public record ScopeGateProperties(@DefaultValue("true") boolean enabled) {}
