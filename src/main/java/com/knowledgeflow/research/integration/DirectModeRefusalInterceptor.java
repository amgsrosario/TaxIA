package com.knowledgeflow.research.integration;

import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.research.source.ResearchProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Exclusividade dos modos (especificação KF-CONV-01 v13, §3.1): com o modo integrado ligado,
 * {@code POST /api/v1/admin/research/investigations} responde sempre 403.
 * <p>
 * Corre no {@code preHandle}, antes da leitura e validação do corpo, por isso a recusa é incondicional (também com
 * corpo inválido) e não tem efeitos. A autenticação e o papel continuam a ser verificados antes, pela cadeia de
 * segurança. Com o modo integrado desligado, não intervém.
 * <p>
 * Registado por {@code ResearchConfiguration} (e não como {@code WebMvcConfigurer} de varrimento), para não entrar
 * nos contextos de teste de fatia web, que não têm {@code ResearchProperties}.
 */
public class DirectModeRefusalInterceptor implements HandlerInterceptor {

    public static final String DIRECT_CREATION_PATH = "/api/v1/admin/research/investigations";

    private final ResearchProperties properties;

    public DirectModeRefusalInterceptor(ResearchProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (properties.getAskIntegration().isEnabled() && "POST".equalsIgnoreCase(request.getMethod())) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN,
                    "Criação directa de investigações desactivada no modo integrado");
        }
        return true;
    }
}
