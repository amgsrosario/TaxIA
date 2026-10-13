// ─────────────────────────────────────────────────────────────────────────────
// Parser de Markdown restrito para o corpo da resposta da TaxIA.
//
// O texto vem do LLM e é tratado como NÃO confiável: o parser só reconhece um
// subconjunto pequeno (títulos, parágrafos, listas com um nível de aninhamento,
// negrito, itálico, código em linha e ligações http(s)) e produz uma árvore de
// dados — nunca HTML. Qualquer HTML presente no texto fica como texto literal; a
// apresentação é feita com elementos React, que escapam o conteúdo.
//
// Não reescreve o texto do backend: apenas o estrutura para leitura. A numeração
// original das listas é preservada (pode ser uma referência legal, ex.: alíneas).
// ─────────────────────────────────────────────────────────────────────────────

export type Inline =
  | { kind: "text"; text: string }
  | { kind: "strong"; children: Inline[] }
  | { kind: "em"; children: Inline[] }
  | { kind: "code"; text: string }
  | { kind: "link"; href: string; children: Inline[] }
  | { kind: "break" };

export type ListItem = { number: number | null; children: Inline[]; sublist: List | null };
export type List = { kind: "list"; ordered: boolean; items: ListItem[] };

export type Block =
  | { kind: "heading"; level: 1 | 2 | 3; children: Inline[] }
  | { kind: "paragraph"; children: Inline[] }
  | List
  | { kind: "rule" };

/** Acima deste tamanho o texto é apresentado sem interpretação (limite defensivo). */
export const MAX_MARKDOWN_LENGTH = 20_000;

/** Só ligações http(s) absolutas são aceites como ligação; o resto fica como texto. */
export function isSafeHref(href: string): boolean {
  return /^https?:\/\/[^\s<>"']+$/i.test(href.trim());
}

const HEADING = /^\s{0,3}(#{1,6})\s+(.*?)(?:\s+#+)?\s*$/;
const ITEM = /^(\s*)(?:([-*+])|(\d{1,3})[.)])\s+(.*)$/;
const RULE = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;

type DraftItem = { number: number | null; lines: string[]; sublist: DraftList | null };
type DraftList = { ordered: boolean; items: DraftItem[] };

export function parseMarkdown(source: string): Block[] {
  const lines = source.replace(/\r\n?/g, "\n").split("\n");
  const blocks: Block[] = [];
  let paragraph: string[] = [];
  let list = null as DraftList | null; // mutada também por flushList()
  let blankInList = false;

  const flushParagraph = () => {
    if (paragraph.length > 0) {
      blocks.push({ kind: "paragraph", children: parseLines(paragraph) });
      paragraph = [];
    }
  };
  const flushList = () => {
    if (list) blocks.push(finishList(list));
    list = null;
    blankInList = false;
  };

  for (const rawLine of lines) {
    const line = rawLine.replace(/\s+$/, "");
    if (line.trim() === "") {
      flushParagraph();
      // Uma linha em branco entre itens não termina a lista (comum no texto do LLM).
      if (list) blankInList = true;
      continue;
    }
    const item = ITEM.exec(line);
    // Texto não indentado (ou após linha em branco) que não é item termina a lista.
    const continuation = !blankInList && /^\s+\S/.test(line);
    if (list && !item && !continuation) flushList();
    if (RULE.test(line)) {
      flushParagraph();
      flushList();
      blocks.push({ kind: "rule" });
      continue;
    }
    const heading = HEADING.exec(line);
    if (heading) {
      flushParagraph();
      flushList();
      const level = Math.min(heading[1].length, 3) as 1 | 2 | 3;
      blocks.push({ kind: "heading", level, children: parseInline(heading[2]) });
      continue;
    }
    if (item) {
      flushParagraph();
      const indented = item[1].length >= 2;
      const ordered = item[3] !== undefined;
      const entry: DraftItem = { number: ordered ? Number(item[3]) : null, lines: [item[4]], sublist: null };
      const current: DraftList | null = list;
      if (current && indented && current.items.length > 0) {
        // Item indentado sob um item existente: um nível de aninhamento.
        const parent = current.items[current.items.length - 1];
        parent.sublist ??= { ordered, items: [] };
        parent.sublist.items.push(entry);
      } else {
        if (!current || current.ordered !== ordered) {
          flushList();
          list = { ordered, items: [] };
        }
        list!.items.push(entry);
      }
      blankInList = false;
      continue;
    }
    if (list) {
      // Continuação indentada do último item (ou do último subitem).
      const last: DraftItem = list.items[list.items.length - 1];
      const target = last.sublist ? last.sublist.items[last.sublist.items.length - 1] : last;
      target.lines.push(line.trim());
      continue;
    }
    paragraph.push(line.trim());
  }
  flushParagraph();
  flushList();
  return blocks;
}

function finishList(draft: DraftList): List {
  return {
    kind: "list",
    ordered: draft.ordered,
    items: draft.items.map((item) => ({
      number: item.number,
      children: parseLines(item.lines),
      sublist: item.sublist ? finishList(item.sublist) : null,
    })),
  };
}

/** Linhas consecutivas do mesmo bloco mantêm a quebra de linha do texto original. */
function parseLines(lines: string[]): Inline[] {
  const result: Inline[] = [];
  lines.forEach((line, index) => {
    if (index > 0) result.push({ kind: "break" });
    result.push(...parseInline(line));
  });
  return result;
}

type Match = { index: number; length: number; node: Inline };

const INLINE_RULES: Array<(text: string) => Match | null> = [
  (text) => {
    const m = /`([^`\n]+)`/.exec(text);
    return m && { index: m.index, length: m[0].length, node: { kind: "code", text: m[1] } };
  },
  (text) => {
    // Aceita um nível de parênteses no destino (ex.: …/IVA_(imposto)).
    const m = /\[([^\]\n]+)\]\(((?:[^()\s]|\([^()\s]*\))+)\)/.exec(text);
    if (!m) return null;
    // Ligação não segura (javascript:, data:, relativa…): mantém só o texto visível.
    const node: Inline = isSafeHref(m[2])
      ? { kind: "link", href: m[2].trim(), children: parseInline(m[1]) }
      : { kind: "text", text: m[1] };
    return { index: m.index, length: m[0].length, node };
  },
  (text) => {
    const m = /\*\*(?=\S)([\s\S]*?\S)\*\*/.exec(text);
    return m && { index: m.index, length: m[0].length, node: { kind: "strong", children: parseInline(m[1]) } };
  },
  (text) => {
    // `__` só fora de palavras (não transforma identificadores como snake__case).
    const m = /(^|[^\w])__(?=\S)([\s\S]*?\S)__(?!\w)/.exec(text);
    if (!m) return null;
    const offset = m[1].length;
    return {
      index: m.index + offset,
      length: m[0].length - offset,
      node: { kind: "strong", children: parseInline(m[2]) },
    };
  },
  (text) => {
    const m = /(^|[^*\w])\*(?=[^\s*])([^*\n]*?[^\s*])\*(?![*\w])/.exec(text);
    if (!m) return null;
    const offset = m[1].length;
    return {
      index: m.index + offset,
      length: m[0].length - offset,
      node: { kind: "em", children: parseInline(m[2]) },
    };
  },
];

export function parseInline(text: string): Inline[] {
  const result: Inline[] = [];
  let rest = text;
  while (rest.length > 0) {
    let best: Match | null = null;
    for (const rule of INLINE_RULES) {
      const match = rule(rest);
      if (match && (best === null || match.index < best.index)) best = match;
    }
    if (best === null) {
      pushText(result, rest);
      break;
    }
    if (best.index > 0) pushText(result, rest.slice(0, best.index));
    result.push(best.node);
    rest = rest.slice(best.index + best.length);
  }
  return result;
}

function pushText(result: Inline[], text: string) {
  const last = result[result.length - 1];
  if (last && last.kind === "text") last.text += text;
  else result.push({ kind: "text", text });
}
