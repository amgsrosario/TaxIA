# TaxIA — Resposta-limite

> **Visão oficial de produto.** A Resposta-limite é o **último patamar automático
> antes do Pedido de parecer**. Não é uma nota lateral nem um *fallback* técnico:
> é uma forma de resposta de pleno direito da TaxIA.
>
> Enquadramento: [taxia-core-principles.md](taxia-core-principles.md) ·
> [taxia-response-model.md](taxia-response-model.md) ·
> [taxia-documented-response-dto.md](taxia-documented-response-dto.md) ·
> [grounding-policy.md](grounding-policy.md) · [roadmap.md](roadmap.md).

## A. Definição

A **Resposta-limite** é o último patamar automático antes do **Pedido de parecer**.

- **Não** é uma resposta fiscal conclusiva.
- **Não** é uma mensagem de erro.
- **É** uma saída **útil, documentada e prudente** para quando a TaxIA não pode
  concluir com segurança.

Quando a TaxIA não dispõe de suporte ou contexto suficiente para responder com
segurança, não deve inventar, extrapolar nem apresentar uma conclusão fiscal.
Deve emitir uma Resposta-limite: indicar que **não é possível concluir com
segurança**, explicar os **elementos em falta**, apresentar apenas o
**enquadramento geral suportado por fontes** e recomendar **Pedido de parecer**
quando a questão exigir apreciação concreta.

## B. Quando é usada

- suporte insuficiente;
- contexto insuficiente;
- factos concretos em falta;
- incerteza elevada;
- risco de conclusão fiscal indevida;
- fontes fracas, contraditórias, desactualizadas ou incompletas;
- pergunta demasiado concreta para resposta automática;
- necessidade provável de apreciação profissional.

## C. O que deve fazer

- **não** apresentar conclusão fiscal final;
- declarar que **não é possível responder com segurança** apenas com os elementos
  disponíveis;
- **explicar os motivos** da limitação;
- apresentar **apenas o enquadramento geral** suportado por fontes;
- **listar os factos/documentos necessários**;
- **indicar fontes relevantes**;
- **recomendar Pedido de parecer** quando aplicável;
- **preservar utilidade** sem fingir certeza.

## D. O que não deve fazer

- inventar;
- extrapolar;
- parecer conclusiva;
- substituir parecer humano;
- dar instrução fiscal individualizada;
- esconder incerteza;
- transformar falta de contexto numa resposta afirmativa ou negativa.

## E. Estrutura padrão

Uma Resposta-limite deve tender a incluir:

1. **Estado da resposta** — declaração explícita de que não há conclusão final.
2. **Motivo da limitação** — por que razão não é possível concluir com segurança.
3. **Elementos em falta** — factos ou documentos em falta.
4. **Enquadramento geral** — o quadro legal/documental aplicável, sem o aplicar ao
   caso concreto.
5. **Fontes relevantes** — referências rastreáveis que sustentam o enquadramento.
6. **Documentos/factos a juntar** — o que o utilizador deve reunir.
7. **Recomendação de Pedido de parecer** — quando a questão exigir apreciação
   concreta.
8. **Indicador de segurança** — sinalização clara de que a resposta é limitada e
   não vinculativa.

## F. Exemplo conceptual

> Exemplo **conceptual** — não é output real do sistema hoje.

**Pergunta:** "Posso deduzir o IVA desta factura de obras?"

**1. Estado da resposta**
Não é possível concluir com segurança sobre a dedutibilidade do IVA desta factura
apenas com os elementos disponíveis. Esta é uma resposta limitada, não uma decisão
final.

**2. Motivo da limitação**
A dedutibilidade do IVA depende de factos concretos que não foram fornecidos: a
natureza da operação, a afectação dos bens/serviços à actividade tributada e o
enquadramento do sujeito passivo. Sem esses elementos, qualquer conclusão seria uma
extrapolação.

**3. Elementos em falta**
- Descrição concreta das obras e do imóvel a que respeitam;
- Afectação do imóvel/obra à actividade (uso profissional, misto ou pessoal);
- Enquadramento do sujeito passivo em IVA (regime, direito à dedução, pro rata);
- Existência de eventuais exclusões do direito à dedução aplicáveis.

**4. Enquadramento geral**
O direito à dedução do IVA rege-se, em geral, pelos artigos **19.º**, **20.º** e
**21.º do CIVA**: o artigo 19.º fixa as condições gerais do direito à dedução, o
20.º delimita as operações que conferem esse direito e o 21.º prevê **exclusões**
do direito à dedução (nomeadamente certas despesas). A aplicação destes preceitos a
uma factura concreta exige apreciação dos factos.

**5. Fontes relevantes**
- CIVA — artigos 19.º, 20.º e 21.º (a confirmar na redacção em vigor).

**6. Documentos/factos a juntar**
- Cópia da factura de obras;
- Descrição da operação e da afectação à actividade;
- Elementos do enquadramento da actividade em IVA.

**7. Recomendação de Pedido de parecer**
Recomenda-se **Pedido de parecer**, juntando a factura, a descrição da operação e o
enquadramento da actividade, para apreciação concreta da dedutibilidade.

**8. Indicador de segurança**
Resposta-limite — sem conclusão fiscal vinculativa; depende de factos e de revisão
profissional.

## G. Relação com o `supportStatus`

Orientação **conceptual** (a afinar na implementação — ver
[grounding-policy.md](grounding-policy.md)):

| `supportStatus` | Tendência de forma de resposta |
|-----------------|--------------------------------|
| `INSUFFICIENT_CONTEXT` | Tende para **Resposta-limite**. |
| `REQUIRES_HUMAN_REVIEW` | Pode gerar **Consulta documentada com limitações**, **Resposta-limite** ou **Pedido de parecer**, conforme o contexto. |
| `PARTIALLY_SUPPORTED` | Tende para **Consulta documentada com limitações**. |
| `REJECTED_UNSUPPORTED` | **Não** deve gerar resposta fiscal — apenas impossibilidade/encaminhamento. |
| `SUPPORTED` | Pode gerar **Consulta documentada**, sem garantia absoluta de 100%. |

O `supportStatus` explica o **suporte técnico**; não é, por si, a forma de resposta
apresentada ao utilizador. A forma é dada pelo `answerType` (ver
[taxia-documented-response-dto.md](taxia-documented-response-dto.md)).

## H. Relação com o Pedido de parecer

A Resposta-limite **não é** o parecer. É o mecanismo que **explica por que razão o
parecer é necessário** e **prepara o caminho** para ele: transforma a falta de
segurança num **pedido organizado de factos e documentos**, em vez de uma
conclusão precipitada. O Pedido de parecer (circuito com intervenção humana
obrigatória) continua a ser o passo seguinte para questões concretas, sensíveis ou
de risco elevado.

## I. Relação com a ingestão massiva

Conhecimento importado em massa **pode não bastar** para responder a uma pergunta
concreta, mas **pode ajudar** a construir o **enquadramento geral** e a **lista de
elementos em falta**. A Resposta-limite permite **aproveitar as fontes** sem as
transformar indevidamente numa conclusão fiscal — alinhando com o princípio de que
a ingestão pode ser massiva mas a conclusão nunca é automática sem segurança.

---

> **Posicionamento (obrigatório).** A Resposta-limite é apoio à decisão. Não
> substitui aconselhamento fiscal profissional nem a responsabilidade do
> profissional qualificado. Em matérias sensíveis, a decisão final é sempre humana.
