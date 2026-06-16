TaxIA / KnowledgeFlow — Contexto do Projecto

O que é este projecto

TaxIA é uma plataforma de consultoria fiscal assistida por IA, desenvolvida pela equipa fundadora. O produto permite que clientes coloquem questões fiscais e recebam respostas com base no conhecimento acumulado da equipa (pareceres, Q&A, legislação comentada).

O backend chama-se KnowledgeFlow internamente.


Stack técnica

ComponenteDetalheBackendSpring Boot 3.4.5, Java 21Base de dadosPostgreSQL 15432 (Docker)MigraçõesFlywayORMHibernate / Spring Data JPASegurançaSpring Security + OAuth2 Resource ServerIAAnthropic Claude Haiku (claude-haiku-4-5-20251001) via APIDocs APISpringDoc OpenAPI / Swagger UIPorta8081

Localização do projecto: C:\Projetos\TaxIA


Arrancar o ambiente

bash# 1. Arrancar o Docker (PostgreSQL)
docker compose up -d

# 2. Arrancar a app no IntelliJ (Run/Debug Configurations)
# Variável de ambiente obrigatória: ANTHROPIC_API_KEY=<chave da Anthropic>

Se a app falhar com "Connection to localhost:15432 refused" → Docker não está a correr.


Serviço de IA


Classe: AnthropicAIService
Modelo: claude-haiku-4-5-20251001
Log de confirmação ao arrancar: AnthropicAIService initialized — model: claude-haiku-4-5-20251001
A chave API é injectada via variável de ambiente ANTHROPIC_API_KEY


System prompt actual (a afinar):

És um assistente especializado em direito fiscal português e europeu.
Responde sempre em português de Portugal, de forma clara, precisa e profissional.
Quando não tiveres certeza, indica-o explicitamente.

Problema conhecido: O system prompt não menciona que volume de negócios > 650.000€ obriga à periodicidade mensal do IVA. Precisa de ser corrigido e expandido.


POC existente

Ficheiro: C:\Projetos\TaxIA\qa-builder.html

Interface React (via CDN, sem build) para:


Fazer perguntas directamente à API
Editar o system prompt em tempo real
Guardar pares Q&A validados
Exportar em JSON para futura ingestão no RAG


Endpoint backend usado: POST /api/ai/ask (criado na sessão anterior)


Próximos passos prioritários

1. Afinar o system prompt

Expandir com conhecimento fiscal português específico — limiares, prazos, obrigações declarativas, referências legais concretas.

2. Arquitectura RAG

O grande activo da empresa é uma base de pares pergunta/resposta de pareceres reais validados por especialistas fiscais, mais documentação técnica.

Plano:


Adicionar extensão pgvector ao PostgreSQL existente
Criar pipeline de ingestão dos documentos e Q&A (formato a confirmar — Word, PDF, Excel?)
Implementar pesquisa semântica: quando um cliente faz uma pergunta, o sistema encontra os Q&A mais similares e inclui-os no contexto enviado ao Claude
O Claude responde com base no conhecimento da equipa, não no genérico


Volume de Q&A: a confirmar com a equipa (quantidade e formato dos ficheiros)

3. Protecção do know-how


Dados via API da Anthropic não são usados para treinar modelos (retenção de 7 dias nos logs, depois apagado)
O know-how fiscal fica na base de dados própria (PostgreSQL + pgvector) — nunca exposto aos modelos externos
Para desenvolvimento, usar sempre a API ou garantir privacidade no claude.ai (já configurado)



Decisões tomadas


RAG em vez de fine-tuning: o conhecimento fica na BD da empresa, actualizável a qualquer momento, mais económico e controlável
pgvector no PostgreSQL existente: evita infra adicional
Claude Haiku: boa relação custo/performance para o caso de uso



Contacto / contexto de negócio


Utilizador principal: António (amgsrosario@gmail.com)
O projecto foi desenvolvido em sessões no Claude Code e Cowork
Esta sessão de contexto foi gerada em 2026-06-16