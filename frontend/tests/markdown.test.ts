// Testes do Markdown restrito da resposta (node --test, sem dependências novas).
import { test } from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MarkdownText } from "../src/components/MarkdownText.ts";
import { MAX_MARKDOWN_LENGTH, isSafeHref, parseMarkdown } from "../src/components/markdown.ts";

const html = (text: string) => renderToStaticMarkup(createElement(MarkdownText, { text }));

// Conteúdos com a estrutura das respostas reais validadas no UI (G1, G2, DEMO1).
const G1 = `# Conservação da contabilidade para efeitos de IRC

As empresas devem conservar os livros, registos contabilísticos e respectivos documentos de suporte em boa ordem durante **10 anos**.

## Detalhes importantes

- O prazo conta-se a partir do exercício a que respeitam (art. 123.º, n.º 4 do CIRC).
- Aplica-se aos documentos de suporte em papel ou em formato electrónico.

## Exceção

Quando a utilização de prejuízos fiscais ultrapasse esse prazo, os documentos devem ser conservados até ao fim do período de reporte.`;

const G2 = `A factura deve ser emitida **até ao 5.º dia útil** seguinte ao momento em que o imposto é devido (art. 36.º, n.º 1 do CIVA).

**Pontos a considerar:**
- O imposto é devido no momento em que o serviço é prestado (art. 7.º do CIVA).
- Prestações continuadas seguem regras próprias.

**Recomendação:** confirme o enquadramento do caso concreto, nos termos do Decreto-Lei n.º 28/2019.`;

const DEMO1 = `O sujeito passivo de IVA deve conservar os registos e documentos de suporte durante **10 anos civis subsequentes**.

Este prazo resulta do art. 52.º do CIVA.`;

test("G1: títulos, parágrafos, secções e lista", () => {
  const out = html(G1);
  assert.match(out, /<h3>Conservação da contabilidade para efeitos de IRC<\/h3>/);
  assert.match(out, /<h4>Detalhes importantes<\/h4>/);
  assert.match(out, /<h4>Exceção<\/h4>/);
  assert.match(out, /<strong>10 anos<\/strong>/);
  assert.equal((out.match(/<li>/g) ?? []).length, 2);
  assert.ok(!/(^|>)\s*#/.test(out), "não deve restar '#' literal");
  assert.ok(!out.includes("**"), "não deve restar '**' literal");
  assert.ok(out.includes("art. 123.º, n.º 4 do CIRC"));
});

test("G2: negrito, lista e recomendação final", () => {
  const out = html(G2);
  assert.match(out, /<strong>até ao 5.º dia útil<\/strong>/);
  assert.match(out, /<p><strong>Pontos a considerar:<\/strong><\/p><ul><li>/);
  assert.match(out, /<p><strong>Recomendação:<\/strong> confirme o enquadramento/);
  assert.ok(out.includes("Decreto-Lei n.º 28/2019"));
  assert.ok(out.includes("art. 36.º, n.º 1 do CIVA"));
});

test("DEMO1: negrito simples e parágrafos curtos", () => {
  const out = html(DEMO1);
  assert.equal(
    out,
    '<div class="md-text"><p>O sujeito passivo de IVA deve conservar os registos e documentos de suporte durante '
      + "<strong>10 anos civis subsequentes</strong>.</p><p>Este prazo resulta do art. 52.º do CIVA.</p></div>",
  );
});

test("o texto do backend não é alterado, só estruturado", () => {
  const visible = (text: string) => html(text).replace(/<[^>]+>/g, "");
  assert.equal(visible("Linha 1\nLinha 2"), "Linha 1Linha 2");
  assert.match(html("Linha 1\nLinha 2"), /Linha 1<br\/>Linha 2/);
  assert.equal(visible("Valor 2 * 3 = 6 e a*b"), "Valor 2 * 3 = 6 e a*b");
  assert.equal(visible("art. 40.º-A, alínea a)"), "art. 40.º-A, alínea a)");
});

test("listas numeradas e itálico", () => {
  assert.match(html("1. Primeiro\n2. Segundo"), /<ol><li value="1">Primeiro<\/li><li value="2">Segundo<\/li><\/ol>/);
  assert.match(html("um *termo* técnico"), /um <em>termo<\/em> técnico/);
});

test("HTML injectado fica como texto escapado", () => {
  const out = html('<script>alert(1)</script>\n<img src=x onerror="alert(2)">\n**<b onclick="x()">t</b>**');
  assert.ok(!out.includes("<script"));
  assert.ok(!out.includes("<img"));
  assert.ok(!out.includes("<b "));
  assert.ok(!/<[^>]*\son\w+=/i.test(out), "nenhum event handler em elementos reais");
  assert.ok(out.includes("&lt;script&gt;alert(1)&lt;/script&gt;"));
  assert.ok(out.includes("&lt;img src=x onerror=&quot;alert(2)&quot;&gt;"));
});

test("ligações perigosas não produzem href", () => {
  for (const href of ["javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,<x>", "vbscript:x", "/relativa", "//evil.example"]) {
    const out = html(`[clique](${href})`);
    assert.ok(!out.includes("href"), `${href} não pode gerar href`);
    assert.ok(out.includes("clique"));
  }
});

test("ligações http(s) são permitidas com rel seguro", () => {
  const out = html("[Portal das Finanças](https://info.portaldasfinancas.gov.pt/pt/x)");
  assert.match(out, /<a href="https:\/\/info\.portaldasfinancas\.gov\.pt\/pt\/x" target="_blank" rel="noopener noreferrer nofollow">Portal das Finanças<\/a>/);
  assert.equal(isSafeHref('https://a.pt/"onmouseover=x'), false);
});

test("Markdown inesperado ou incompleto não quebra a apresentação", () => {
  for (const text of ["**sem fecho", "# ", "-", "```\ncódigo\n```", "> citação", "---", "| a | b |\n|---|---|", "*".repeat(5000)]) {
    assert.doesNotThrow(() => html(text));
  }
  assert.match(html("**sem fecho"), /\*\*sem fecho/);
  assert.equal(parseMarkdown("").length, 0);
});

// Regressões da revisão independente.
test("M1: listas separadas por linhas em branco mantêm uma única lista e a numeração", () => {
  assert.equal(
    html("1. Primeiro\n\n2. Segundo\n\n3. Terceiro"),
    '<div class="md-text"><ol><li value="1">Primeiro</li><li value="2">Segundo</li><li value="3">Terceiro</li></ol></div>',
  );
});

test("M1: subitens indentados ficam aninhados e a numeração continua", () => {
  assert.equal(
    html("1. Regime\n   - sub a\n   - sub b\n2. Outro"),
    '<div class="md-text"><ol><li value="1">Regime<ul><li>sub a</li><li>sub b</li></ul></li><li value="2">Outro</li></ol></div>',
  );
});

test("M1: numeração original não sequencial (alíneas) é preservada", () => {
  assert.match(html("3) alínea c do n.º 3"), /<li value="3">alínea c do n.º 3<\/li>/);
  assert.match(html("10) item"), /<li value="10">item<\/li>/);
});

test("lista termina num parágrafo após linha em branco", () => {
  assert.equal(
    html("- a\n- b\n\nTexto final."),
    '<div class="md-text"><ul><li>a</li><li>b</li></ul><p>Texto final.</p></div>',
  );
});

test("m1: '>' no início da linha é mantido como texto", () => {
  assert.match(html("> 50 000 € de volume de negócios"), /<p>&gt; 50 000 € de volume de negócios<\/p>/);
});

test("m2: '#' final só é removido quando é marcador de fecho", () => {
  assert.match(html("# Linguagem C#"), /<h3>Linguagem C#<\/h3>/);
  assert.match(html("## Título ##"), /<h4>Título<\/h4>/);
});

test("m3: '__' dentro de palavras não produz negrito", () => {
  assert.ok(!html("snake__case__x").includes("<strong>"));
  assert.match(html("um __termo__ aqui"), /um <strong>termo<\/strong> aqui/);
});

test("m6: destino com parênteses e ligação insegura sem restos", () => {
  assert.match(html("[IVA](https://pt.wikipedia.org/wiki/IVA_(imposto))"), /href="https:\/\/pt\.wikipedia\.org\/wiki\/IVA_\(imposto\)" target="_blank" rel="noopener noreferrer nofollow">IVA<\/a>/);
  assert.equal(html("[x](javascript:alert(1))"), '<div class="md-text"><p>x</p></div>');
});

test("m4: texto acima do limite é apresentado sem interpretação e em tempo limitado", () => {
  const big = "[".repeat(MAX_MARKDOWN_LENGTH + 1);
  const started = Date.now();
  const out = html(big);
  assert.ok(out.includes("md-text-plain"));
  assert.ok(Date.now() - started < 500);
  const atLimit = "**a ".repeat(Math.floor(MAX_MARKDOWN_LENGTH / 4));
  const t0 = Date.now();
  html(atLimit);
  assert.ok(Date.now() - t0 < 2000, "entrada adversarial no limite deve continuar rápida");
});
