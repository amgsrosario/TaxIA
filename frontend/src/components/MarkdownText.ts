import { createElement, Fragment } from "react";
import type { ReactNode } from "react";
import { MAX_MARKDOWN_LENGTH, parseMarkdown } from "./markdown.ts";
import type { Block, Inline, List } from "./markdown.ts";

// Apresentação do Markdown restrito (ver markdown.ts) apenas com elementos React:
// sem dangerouslySetInnerHTML, sem atributos vindos do texto além de href http(s).
// Escrito com createElement (sem JSX) para poder ser exercitado pelos testes Node.

function renderInline(nodes: Inline[]): ReactNode[] {
  return nodes.map((node, i) => {
    switch (node.kind) {
      case "text":
        return createElement(Fragment, { key: i }, node.text);
      case "strong":
        return createElement("strong", { key: i }, ...renderInline(node.children));
      case "em":
        return createElement("em", { key: i }, ...renderInline(node.children));
      case "code":
        return createElement("code", { key: i }, node.text);
      case "link":
        return createElement(
          "a",
          { key: i, href: node.href, target: "_blank", rel: "noopener noreferrer nofollow" },
          ...renderInline(node.children),
        );
      case "break":
        return createElement("br", { key: i });
    }
  });
}

// Os títulos ficam abaixo do <h1> da página, para a resposta não parecer um documento.
const HEADING_TAGS = { 1: "h3", 2: "h4", 3: "h5" } as const;

// A numeração original é preservada (value), mesmo que não seja sequencial.
function renderList(list: List, key: number): ReactNode {
  return createElement(
    list.ordered ? "ol" : "ul",
    { key },
    ...list.items.map((item, j) =>
      createElement(
        "li",
        { key: j, value: list.ordered && item.number !== null ? item.number : undefined },
        ...renderInline(item.children),
        item.sublist ? renderList(item.sublist, -1) : null,
      ),
    ),
  );
}

function renderBlock(block: Block, i: number): ReactNode {
  switch (block.kind) {
    case "heading":
      return createElement(HEADING_TAGS[block.level], { key: i }, ...renderInline(block.children));
    case "paragraph":
      return createElement("p", { key: i }, ...renderInline(block.children));
    case "list":
      return renderList(block, i);
    case "rule":
      return createElement("hr", { key: i });
  }
}

export function MarkdownText({ text, className }: { text: string; className?: string }) {
  const classes = className ? `md-text ${className}` : "md-text";
  // Texto anormalmente longo: apresentado tal como vem, sem interpretação.
  if (text.length > MAX_MARKDOWN_LENGTH) {
    return createElement("div", { className: `${classes} md-text-plain` }, text);
  }
  return createElement("div", { className: classes }, ...parseMarkdown(text).map(renderBlock));
}
