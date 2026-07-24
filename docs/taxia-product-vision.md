# TaxIA — Visão de produto: consultoria fiscal assistida por IA

> Documento de alinhamento de produto. Orienta as decisões técnicas e funcionais
> das próximas fases. Não descreve apenas o que já existe — fixa o rumo.
>
> Estado técnico de referência: pipeline de curadoria, publicação aplicacional e
> RAG validados; correcção do indexer no commit `b79f7b8`.

## 1. Princípio central

A TaxIA **não é um chatbot fiscal genérico**.

A TaxIA é uma **plataforma de consultoria fiscal assistida por IA**, baseada em
conhecimento documentado, fontes rastreáveis, grau de confiança, alertas de risco
e intervenção humana nos pontos críticos.

O valor não está no modelo de linguagem em si, mas no **conhecimento fiscal curado
pela equipa** e na disciplina com que esse conhecimento é ingerido, validado,
publicado e apresentado. O modelo é o veículo; o activo é a base de conhecimento.

## 2. Definição do produto

A TaxIA entrega respostas fiscais que são:

- **documentadas** — assentes em conhecimento validado, não em memória do modelo;
- **rastreáveis** — cada resposta liga-se às fontes que a sustentam (FAQ oficial,
  legislação, parecer interno);
- **qualificadas** — com grau de confiança e nível de suporte explícitos;
- **prudentes** — com alertas fiscais e sinalização de risco;
- **supervisionadas** — com intervenção humana obrigatória nos casos críticos;
- **auditáveis** — com separação clara entre as etapas do ciclo de vida.

### Separação de etapas (fronteiras que não se devem confundir)

| Etapa | O que é | O que **não** é |
|-------|---------|-----------------|
| **Ingestão** | Trazer matéria-prima para a base (ex.: FAQs oficiais da AT) em estado bruto/quarentena | Não é publicação nem resposta ao cliente |
| **Curadoria** | Redigir/estruturar a resposta TaxIA (resposta curta, técnica, fontes, condições) | Não é validação automática |
| **Validação** | Confirmar que o caso cumpre os critérios editoriais e de qualidade | Não torna o caso visível por si só |
| **Publicação** | Tornar o caso elegível para RAG (cria embedding) via endpoint aplicacional | Não é, necessariamente, visibilidade directa ao cliente |
| **Resposta ao cliente** | Combinar RAG + grounding + qualificação para produzir a resposta final | Não é devolver o texto cru do modelo |

Estas fronteiras estão hoje materializadas no ciclo
`IMPORTED → PENDING_REVIEW → VALIDATED → (publicado)` e no facto de a publicação e
a indexação serem operações **separadas** da validação.

## 3. O que a TaxIA **não** deve ser

- Uma IA que responde **apenas com base no modelo**, sem conhecimento curado.
- Um **repositório indiscriminado de FAQs** publicadas sem controlo editorial.
- Uma ferramenta que **esconde a incerteza** ou apresenta tudo com a mesma
  aparência de confiança.
- Uma **substituta total** do fiscalista/contabilista.

> **Nota de posicionamento (obrigatória):** a TaxIA é um instrumento de apoio à
> decisão e de produtividade para profissionais. Não substitui aconselhamento
> fiscal profissional nem a responsabilidade do profissional qualificado. Em
> matérias sensíveis, a decisão final é sempre humana.

## 4. O que a TaxIA **deve** ser

- Uma **base de conhecimento fiscal documentada**, propriedade da equipa.
- Um **motor RAG com fontes**, que responde com o conhecimento da equipa e cita
  a sua proveniência.
- Uma ferramenta de **pré-consulta e apoio à decisão**.
- Uma **camada de triagem e qualificação** (o que é seguro responder, o que exige
  revisão, o que não é publicável).
- Um **instrumento de produtividade** para consultores.
- Uma ferramenta **transparente** para o cliente e para o profissional.

## 5. Modelo de resposta ideal

Cada resposta ao utilizador deve **tender a** incluir:

1. **Resposta curta** — o essencial, directo.
2. **Explicação técnica** — o raciocínio fiscal.
3. **Fundamentos legais** — artigos, códigos, ofícios aplicáveis.
4. **Fontes** — referências rastreáveis (FAQ oficial, legislação, parecer).
5. **Condições** — quando é que a resposta se aplica.
6. **Exclusões** — quando é que **não** se aplica.
7. **Alertas** — riscos, prazos, obrigações associadas.
8. **Nível de suporte** — quão bem o contexto sustenta a resposta.
9. **Grau de confiança** — indicação de fiabilidade.
10. **Necessidade de revisão humana** — sinalizada de forma explícita.

Estes campos alinham com o que a base já suporta (resposta curta e técnica,
fontes tipificadas) e com o que o *grounding* já calcula (nível de suporte,
revisão humana). Ver [grounding-policy.md](grounding-policy.md).

## 6. Escala de qualidade sugerida

Escala editorial para classificar o estado de qualidade de um caso/resposta:

| Nível | Significado |
|-------|-------------|
| **INFORMATIVA** | Conteúdo bruto/orientador, sem curadoria completa |
| **DOCUMENTADA** | Tem fontes e estrutura, ainda não validada |
| **VALIDADA** | Cumpre os critérios editoriais e de qualidade |
| **PUBLICADA** | Elegível para RAG (tem embedding) |
| **REVISÃO HUMANA RECOMENDADA** | Pode responder, mas convém validação profissional |
| **REVISÃO HUMANA OBRIGATÓRIA** | Não deve ser resposta autónoma final |
| **NÃO PUBLICÁVEL** | Não deve entrar no RAG nem ser apresentada |

Esta escala é uma **camada editorial** sobreposta ao ciclo técnico de estados; não
substitui `curation_status` nem `supportStatus`, complementa-os.

## 7. Política de ingestão massiva

- A **ingestão pode ser industrial** (volume elevado, automatizada).
- A **publicação não deve ser industrial sem filtros** — publicar é um acto
  editorial deliberado, caso a caso.
- As **FAQs oficiais entram como matéria-prima** (estado bruto/quarentena), nunca
  auto-publicadas. Ver [at-faq-ingestion-pilot.md](at-faq-ingestion-pilot.md).
- As **respostas TaxIA devem ser estruturadas e qualificadas** antes de publicadas.
- As **alterações de fonte devem ser detectadas** (a fonte oficial pode mudar).
- Os **casos críticos devem ir para revisão** antes de qualquer exposição.

## 8. Política de risco

| `risk_level` | Exigência |
|--------------|-----------|
| **LOW** | Pode admitir validação leve |
| **MEDIUM** | Exige revisão normal |
| **HIGH** | Exige revisão humana reforçada |

- Temas sensíveis **podem** ser publicados, mas a resposta deve sair marcada como
  `REQUIRES_HUMAN_REVIEW`.
- **Casos HIGH não devem ser apresentados como resposta autónoma final** — servem
  de apoio, com revisão humana obrigatória por cima.

> Estado actual: o `supportStatus = REQUIRES_HUMAN_REVIEW` observado é despoletado
> por palavras-chave de risco na **pergunta** (`ContextSufficiencyEvaluator.
> HIGH_RISK_KEYWORDS`, ex.: "imóvel"), **não** pelo `risk_level` do caso na BD.
> Ligar o `risk_level` da BD ao `supportStatus` é uma decisão técnica futura
> (ver secção 10).

## 9. Implicações técnicas futuras

Decisões a considerar (registo de intenção, não compromisso de calendário):

- **Adicionar metadados de qualidade à resposta** (grau de confiança, nível de
  suporte, revisão humana) de forma consistente e estruturada.
- **Expor fontes e suporte no frontend** — hoje a UI de curadoria não apresenta
  ainda a resposta qualificada ao cliente.
- **Distinguir publicação técnica de visibilidade ao cliente** — um caso pode
  estar publicado (elegível para RAG) sem estar necessariamente visível ao
  cliente final.
- **Criar filas de revisão** — trabalho de curadoria e revisão organizado.
- **Criar importação massiva controlada** — volume com filtros e quarentena.
- **Criar painel de alterações de fontes** — detecção e triagem de mudanças nas
  fontes oficiais.
- **Ligar `risk_level` da BD ao `supportStatus`** — hoje são independentes.
- **Melhorar a UX para explicar `REQUIRES_HUMAN_REVIEW`** — comunicar a incerteza
  ao utilizador de forma clara, não como erro.

## 10. Estado actual do piloto

- **2 casos MEDIUM publicados e recuperáveis por RAG:**
  - `AT-FAQ-5930` (deduções a rendimentos prediais);
  - `AT-FAQ-2721` (AIMI: opção pela tributação conjunta).
- **2 casos HIGH validados mas não publicados:**
  - `AT-FAQ-0959`;
  - `AT-FAQ-4624`.
- **Pipeline técnico validado:** curadoria, publicação aplicacional e RAG; bug do
  indexer corrigido em `b79f7b8`.
- **A base de conhecimento já suporta** estados, fontes, revisão, publicação e
  embeddings.

**Próxima fase:** focar o *modelo de resposta documentada* (secção 5) e a
*ingestão massiva controlada* (secção 7), mantendo a disciplina de que **publicar
é sempre um acto editorial deliberado, nunca industrial e cego**.

## Documentos relacionados

- [grounding-policy.md](grounding-policy.md) — política de *grounding* e suporte.
- [at-faq-ingestion-pilot.md](at-faq-ingestion-pilot.md) — ingestão controlada de FAQs da AT.
- [knowledge-qa-import.md](knowledge-qa-import.md) — importação de pares Q&A.
- [knowledge-qa-review-checklist.md](knowledge-qa-review-checklist.md) — checklist de revisão.
- [taxia-curation-backoffice.md](taxia-curation-backoffice.md) — backoffice de curadoria.
- [roadmap.md](roadmap.md) — roadmap geral.
