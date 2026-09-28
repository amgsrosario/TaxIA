# TaxIA / KnowledgeFlow — CLAUDE.md

Este ficheiro não é um guia operacional. As instruções para agentes estão em
[`AGENTS.md`](AGENTS.md), que prevalece sobre qualquer versão anterior deste
ficheiro.

## Onde procurar

- [`AGENTS.md`](AGENTS.md) — regras de trabalho, autonomia, verificação,
  segurança e decisões reservadas a humanos.
- [`README.md`](README.md) — stack, execução local, testes, endpoints e
  migrações.
- [`docs/`](docs/) — documentação do projecto, em particular:
  - [`docs/adr/`](docs/adr/) — decisões arquitecturais (ADR);
  - [`docs/taxia-core-principles.md`](docs/taxia-core-principles.md) e
    [`docs/taxia-product-vision.md`](docs/taxia-product-vision.md) — princípios
    e visão de produto;
  - [`docs/ai-providers.md`](docs/ai-providers.md) — fornecedores de IA e
    configuração;
  - [`docs/grounding-policy.md`](docs/grounding-policy.md),
    [`docs/taxia-risk-visibility-policy.md`](docs/taxia-risk-visibility-policy.md)
    e [`docs/taxia-boundary-answer.md`](docs/taxia-boundary-answer.md) —
    políticas de grounding, risco e resposta-limite;
  - [`docs/roadmap.md`](docs/roadmap.md) — prioridades.

## Confirmar nas fontes actuais

Decisões de produto e de arquitectura, versões, portas, perfis, modelos,
endpoints e comandos devem ser confirmados no código, na configuração, no
Docker Compose, nas migrações, nos ADR e na documentação actual. Não confiar
em contexto de sessões anteriores nem em versões antigas deste ficheiro.

## System prompt

O system prompt do TaxIA está em
`src/main/java/com/knowledgeflow/ai/taxia/TaxiaSystemPrompt.java` e contém
apenas comportamento estável. Não lhe acrescentar factos fiscais voláteis
(limiares, taxas, prazos, datas, artigos, obrigações, excepções ou regimes):
esse conhecimento pertence à base de conhecimento governada e ao RAG.
