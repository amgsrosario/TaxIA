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

## 1-A. A TaxIA é profissional, mesmo em demo

A TaxIA é uma **ferramenta profissional de consulta fiscal documentada assistida
por IA**. O foco do produto é **100% profissional**.

- **Não existe "TaxIA-lite".** Não há uma versão para leigo com resposta
  simplificada como desenho principal.
- **A demo mostra a mesma lógica profissional** — resposta documentada, fontes,
  limitações, Resposta-limite, grau de incerteza, encaminhamento para Pedido de
  parecer e linguagem clara mas profissional.
- **As diferenças entre contextos de uso são apenas comerciais, operacionais ou de
  permissões** — nunca uma descida da qualidade, profundidade, fundamentação ou
  estrutura da resposta.
- A **linguagem clara** não é simplificação: é rigor comunicado com clareza, para
  um público que sabe ler fundamentação fiscal.

Uma demo pode ser limitada **comercialmente** (volume, funcionalidades), mas nunca
**conceptualmente** quanto à qualidade da resposta.

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

Esta escala materializa-se numa **sequência oficial de formas de resposta**, do
maior para o menor grau de conclusão:

1. **Consulta documentada** — resposta com fundamentação legal normal;
2. **Consulta documentada com limitações** — resposta com mais condições,
   exclusões e fontes, e limites explícitos;
3. **Resposta-limite** — último patamar automático: não conclui, explica o que
   falta, dá o enquadramento geral e recomenda Pedido de parecer;
4. **Pedido de parecer** — circuito com intervenção humana obrigatória.

## 3-A. A Resposta-limite é o último patamar automático

Quando a TaxIA **não pode concluir com segurança**, não inventa, não extrapola nem
apresenta conclusão fiscal: emite uma **Resposta-limite**. Esta declara que não é
possível concluir com segurança, explica os elementos em falta, apresenta apenas o
enquadramento geral suportado por fontes e recomenda Pedido de parecer quando a
questão exigir apreciação concreta.

A Resposta-limite é **visão oficial da TaxIA**, não um *fallback* técnico:
"não concluir" também é uma resposta útil. Ver
[taxia-boundary-answer.md](taxia-boundary-answer.md).

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

Os modos são **vistas/permissões sobre a mesma resposta profissional** — controlam
o detalhe visível, não a qualidade conceptual (ver [Princípio 1-A](#1-a-a-taxia-é-profissional-mesmo-em-demo)):

- **PROFESSIONAL** (utilizador externo profissional) — resposta documentada
  completa: fundamentação, condições, fontes completas, alertas e sinais de risco.
- **DEMO** — o **mesmo** produto profissional, apenas limitado comercial ou
  operacionalmente; **não** é uma resposta simplificada.
- **ADMIN** — acrescenta diagnóstico, scores, estados, notas internas, auditoria e
  curadoria.

> O modo `CLIENT` que aparece no contrato técnico existente
> ([taxia-documented-response-dto.md](taxia-documented-response-dto.md)) designa,
> por razões técnicas, o **utilizador externo** — **não** um leigo e **não** uma
> resposta simplificada. Os nomes conceptuais preferíveis para o futuro são
> **PROFESSIONAL / DEMO / ADMIN**.

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
- [taxia-boundary-answer.md](taxia-boundary-answer.md) — Resposta-limite (último patamar automático).
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
