/**
 * 极简 Markdown 渲染 (避免引入额外依赖)。支持: 标题/段落/列表/code/blockquote/[S#] 引用标号。
 * 返回 HTML 字符串, 用 dangerouslySetInnerHTML 渲染。
 * 安全: 输出经过手动转义, 不拼接未转义 user input; [S#] 标号特殊处理。
 */
function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** 替换 [S#] 为可悬停的引用标号 (内联 span) */
function injectCitations(html: string): string {
  return html.replace(/\[S(\d+)]/g, (_, n) =>
    `<span class="cite-ref" data-sid="S${n}">[${n}]</span>`);
}

function inline(s: string): string {
  let out = esc(s);
  // 行内 code `xxx`
  out = out.replace(/`([^`]+)`/g, '<code>$1</code>');
  // 粗体 **x**
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  // 斜体 *x*
  out = out.replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>');
  return injectCitations(out);
}

export function renderMarkdown(src: string): string {
  const lines = src.split('\n');
  const out: string[] = [];
  let i = 0;
  while (i < lines.length) {
    const ln = lines[i];
    // 代码块
    if (ln.startsWith('```')) {
      const code: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) {
        code.push(lines[i]);
        i++;
      }
      out.push(`<pre><code>${esc(code.join('\n'))}</code></pre>`);
      i++;
      continue;
    }
    // 标题
    const h = /^(#{1,3})\s+(.+)$/.exec(ln);
    if (h) {
      const lvl = h[1].length;
      out.push(`<h${lvl}>${inline(h[2])}</h${lvl}>`);
      i++;
      continue;
    }
    // 引用
    if (ln.startsWith('> ')) {
      const q: string[] = [];
      while (i < lines.length && lines[i].startsWith('> ')) {
        q.push(lines[i].slice(2));
        i++;
      }
      out.push(`<blockquote>${inline(q.join(' '))}</blockquote>`);
      continue;
    }
    // 无序列表
    if (/^[-*]\s+/.test(ln)) {
      const items: string[] = [];
      while (i < lines.length && /^[-*]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^[-*]\s+/, ''));
        i++;
      }
      out.push('<ul>' + items.map(it => `<li>${inline(it)}</li>`).join('') + '</ul>');
      continue;
    }
    // 有序列表
    if (/^\d+\.\s+/.test(ln)) {
      const items: string[] = [];
      while (i < lines.length && /^\d+\.\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\d+\.\s+/, ''));
        i++;
      }
      out.push('<ol>' + items.map(it => `<li>${inline(it)}</li>`).join('') + '</ol>');
      continue;
    }
    // 空行
    if (ln.trim() === '') {
      i++;
      continue;
    }
    // 普通段落 (合并连续非空行)
    const para: string[] = [ln];
    i++;
    while (i < lines.length && lines[i].trim() !== '' && !/^(#{1,3}\s|[-*]\s|\d+\.\s|```|>\s)/.test(lines[i])) {
      para.push(lines[i]);
      i++;
    }
    out.push(`<p>${inline(para.join(' '))}</p>`);
  }
  return out.join('\n');
}