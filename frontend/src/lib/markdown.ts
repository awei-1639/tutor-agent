/**
 * Markdown 渲染: 用 marked 18.x, 在渲染完成后正则替换 [S#] → cite-ref span。
 * marked 18 的 Renderer.text 改签名为 token, 不再方便拦截, 所以走 post-processing。
 */
import { marked } from 'marked';
import DOMPurify from 'dompurify';

export function renderMarkdown(src: string, citationScope = ''): string {
  marked.setOptions({ gfm: true, breaks: false });
  let html = marked.parse(src, { async: false }) as string;
  // 把 [S#] 替换为可点击 cite-ref span (正则匹配后端输出格式)
  html = html.replace(/\[S(\d+)]/g, (_, n) =>
    `<span class="cite-ref" data-sid="S${n}" data-cite-key="${citationScope}:S${n}">[${n}]</span>`);
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      'p', 'br', 'strong', 'em', 'del', 'ul', 'ol', 'li', 'blockquote',
      'pre', 'code', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'a', 'span'
    ],
    ALLOWED_ATTR: ['href', 'title', 'target', 'rel', 'class', 'data-sid', 'data-cite-key'],
    FORBID_TAGS: ['style', 'script', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'svg', 'math']
  });
}
