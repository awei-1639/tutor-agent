/**
 * Markdown 渲染: 用 marked 处理, 自定义渲染器把 [S#] 转成可点击 cite-ref。
 * 替代之前手写实现 (无法正确处理嵌套粗体/列表/代码块)。
 */
import { marked, Renderer } from 'marked';

const renderer = new Renderer();
// 段落里把 [S#] 转成 cite-ref span
renderer.text = (text: any) => {
  const t = String(text);
  const withCite = t.replace(/\[S(\d+)]/g, (_, n) =>
    `<span class="cite-ref" data-sid="S${n}">[${n}]</span>`);
  // HTML 转义: marked 已经把裸 HTML 转义了, 但 cite-ref 是我们手动注入的 span,
  // 它是安全的 HTML, 不需要再转义。
  return withCite;
};

export function renderMarkdown(src: string): string {
  marked.setOptions({ gfm: true, breaks: false });
  // marked 返回 string (同步模式)
  const html = marked.parse(src, { async: false }) as string;
  return html;
}