# TaxIA — Princípios basilares

> **Nota de natureza.** Este documento define princípios estruturantes do produto
> TaxIA. Deve orientar decisões futuras de produto, backend, frontend, RAG,
> curadoria, publicação e visibilidade. Em caso de dúvida entre implementação
> rápida e estes princípios, **prevalecem estes princípios**.
>
> É uma síntese normativa — princípios "constitucionais" — e **não substitui** os
> documentos longos já existentes; condensa-os e dá-lhes força de referência
> interna. Ver [Relação com documentos existentes](#11-relação-com-documentos-existentes).

## 1. A TaxIA não é um chatbot fiscal genérico

A TaxIA é uma **plataforma de consultoria fiscal assistida por IA**.

A IA não responde apenas "da cabeça". Cada resposta deve ser baseada em
**conhecimento documentado**, em **fontes rastreáveis** e sob **controlo de risco**.
O valor está no conhecimento fiscal curado pela equipa, não no modelo de linguagem
em si — o modelo é o veículo, o activo é a base de conhecimento.

## 2. Dois circuitos principais

**A. Pedido de parecer**
- Intervenção humana **obrigatória**.
- Para questões concretas, sensíveis, individualizadas ou de risco elevado.
- A IA pode preparar o dossier, mas **não substitui** o humano.

**B. Pesquisa de legislação/documentação assistida por IA**
- Resposta automática **possível**.
- Para questões genéricas ou suficientemente enquadradas.
- Deve apresentar **fontes, fundamentos, incerteza e limites**.

## 3. A incerteza aumenta a exigência de documentação

Quanto maior a incerteza, **maior a robustez da fundamentação** legal/documental,
**maior a explicitação de limites** e **maior a probabilidade de encaminhamento**
para Pedido de parecer.

Escala conceptual:

- **suporte forte** → resposta documentada, com fundamentação legal normal;
- **incerteza moderada** → resposta documentada com mais condições, exclusões,
  fontes e limitações;
- **incerteza elevada** → limitação forte ou encaminhamento para Pedido de parecer.

## 4. A resposta automática nunca é 100% garantida

Mesmo quando bem suportada, a resposta automática deve indicar que:

- resulta das **fontes disponíveis**;
- depende da **actualidade** dessas fontes;
- pode depender de **factos concretos** do caso;
- **não substitui aconselhamento profissional** quando exista caso concreto
  relevante.

## 5. Publicação técnica não é visibilidade livre

Distinguir sempre:

- **publicação técnica no RAG** (o caso torna-se pesquisável / indexado);
- **visibilidade para cliente**;
- **visibilidade para profissional**;
- **visibilidade para admin/curador**.

Um caso pode estar **publicado tecnicamente** e, ainda assim, **não** ser resposta
autónoma para o cliente.

## 6. A regra mais restritiva prevalece

Quando os sinais entram em conflito, **prevalece o mais prudente**. Sinais a pesar:

- `risk_level`;
- `supportStatus`;
- keywords sensíveis;
- `freshnessStatus`;
- qualidade das fontes;
- falta de contexto;
- modo de utilização.

## 7. Tratamento dos casos HIGH

Os casos HIGH **podem** ser tecnicamente publicados no RAG, mas:

- têm **travões**;
- exigem revisão humana **recomendada ou obrigatória**;
- por defeito são **`PROFESSIONAL_ONLY`**;
- se visíveis ao cliente, devem sair com **aviso forte**;
- **nunca** devem ser apresentados como resposta autónoma final sem limitação clara.

## 8. Modos de utilização

- **CLIENT** — linguagem clara, fontes e alertas, sem ruído interno.
- **PROFESSIONAL** — maior detalhe técnico, condições, fontes completas e sinais
  de risco.
- **ADMIN** — diagnóstico, scores, estados, notas internas, auditoria e curadoria.

## 9. Pedir factos em vez de fingir certeza

Quando faltam elementos relevantes, a TaxIA deve:

- **pedir dados adicionais**;
- **indicar os factos necessários**;
- **explicar** que a conclusão depende desses elementos;
- ou **encaminhar** para Pedido de parecer.

Nunca deve fabricar certeza para preencher lacunas.

## 10. Escala com responsabilidade

A **ingestão pode ser massiva**. A **publicação e a visibilidade devem ser
controladas**. O objectivo é escalar a recolha e a pré-curadoria mantendo
**rastreabilidade, qualidade e supervisão humana** nos pontos críticos.

## 11. Relação com documentos existentes

- [taxia-product-vision.md](taxia-product-vision.md) — visão de produto.
- [taxia-response-model.md](taxia-response-model.md) — modelo de resposta documentada.
- [taxia-documented-response-dto.md](taxia-documented-response-dto.md) — contrato técnico da resposta.
- [roadmap.md](roadmap.md) — roadmap e fase de consultoria assistida.
- [grounding-policy.md](grounding-policy.md) — política de *grounding* e suporte.

## 12. Implicações imediatas

- **Não** publicar casos HIGH como resposta livre ao cliente sem política explícita.
- Desenhar **primeiro** a política de risco/visibilidade.
- **Depois** implementar o enriquecimento da resposta documentada.
- **Só então** avançar para a ingestão massiva controlada.

---

> **Posicionamento (obrigatório).** A TaxIA é apoio à decisão e não substitui
> aconselhamento fiscal profissional nem a responsabilidade do profissional
> qualificado. Em matérias sensíveis, a decisão final é sempre humana.
