// seed 技能描述增肥: 只扩写 description (30-50字 → 120-200字), 其余字段一律不动。
// 用法: node scripts/enrich_seed_descriptions.mjs [--dry]
// 安全: 先备份 seed_skills.json; 校验失败的单条保留原文; id/别名/难度/前置/进阶永不改写。
import { readFileSync, writeFileSync, copyFileSync } from 'node:fs';

const envFile = Object.fromEntries(
  readFileSync(new URL('../.env', import.meta.url), 'utf8')
    .split(/\r?\n/).filter(l => l.includes('=') && !l.startsWith('#'))
    .map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()])
);
const env = { ...envFile, ...process.env };
const DRY = process.argv.includes('--dry');
const SEED = new URL('../graph_data/seed_skills.json', import.meta.url);
const data = JSON.parse(readFileSync(SEED, 'utf8'));
const skills = data.skills;

const SYSTEM = '你是知识库编辑。对给定的技能条目描述进行扩写。要求: 只输出 JSON。';
const USER_TEMPLATE = ids => `请为以下每个技能扩写 description 字段。
要求:
- 扩写后 120-200 字, 包含: 客观定义(是什么) / 涵盖的核心内容(学什么) / 在 AI 学习与求职语境中的作用(为什么重要) / 典型应用场景(用在哪);
- 基于通用共识, 禁止编造具体价格、版本号、年份;
- 语言客观简洁, 不用第一人称, 不出现"该技能"以外的评价性措辞;
- 严格保持技能原含义不变, 禁止扩大或偏移主题。
输入条目:
${ids.join('\n')}

输出 JSON: {"descriptions": {"<id>": "<扩写后的description>", ...}}`;

// .env 的 DEEPSEEK_* 是历史命名, 实际指向智谱端点; 模型可用 ENRICH_MODEL 覆盖。
const MODEL = env.ENRICH_MODEL || 'glm-4-flash';

async function expand(batch) {
  const res = await fetch(`${env.DEEPSEEK_BASE_URL}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${env.DEEPSEEK_API_KEY}` },
    body: JSON.stringify({
      model: MODEL, temperature: 0.3, max_tokens: 4000,
      response_format: { type: 'json_object' },
      messages: [{ role: 'system', content: SYSTEM }, { role: 'user', content: USER_TEMPLATE(batch) }],
    }),
    signal: AbortSignal.timeout(180_000),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 150)}`);
  const out = JSON.parse(JSON.parse(await res.text())['choices'][0]['message']['content']);
  return out.descriptions ?? {};
}

const BATCH = 10;
const kept = [], enriched = [], dropped = [];
for (let i = 0; i < skills.length; i += BATCH) {
  const batch = skills.slice(i, i + BATCH);
  const lines = batch.map(s => `- id: ${s.id} | 名称: ${s.name} | 原描述: ${s.description}`);
  try {
    const desc = await expand(lines);
    for (const s of batch) {
      const next = (desc[s.id] ?? '').trim();
      if (typeof next === 'string' && next.length >= 80 && next.length <= 400 && !next.includes(s.id)) {
        enriched.push(s.id);
        kept.push([s.id, next]);
      } else {
        dropped.push(s.id);
      }
    }
  } catch (e) {
    console.error(`批次 ${i / BATCH + 1} 失败, 保留原文: ${e.message}`);
    dropped.push(...batch.map(s => s.id));
  }
  process.stdout.write(`\r进度 ${Math.min(i + BATCH, skills.length)}/${skills.length} (成功 ${enriched.length})`);
}
console.log();

if (DRY) {
  console.log(`[dry] 将扩写 ${enriched.length} 条, 保留原文 ${dropped.length} 条`);
  for (const [id, d] of kept.slice(0, 3)) console.log(`\n[${id}]\n${d}`);
  process.exit(0);
}

copyFileSync(SEED, new URL(`../graph_data/seed_skills.backup-${new Date().toISOString().slice(0, 10)}.json`, import.meta.url));
for (const [id, d] of kept) {
  const s = skills.find(x => x.id === id);
  s.description = d; // 其余字段原样保留
}
data.generated_at = new Date().toISOString();
writeFileSync(SEED, JSON.stringify(data, null, 2) + '\n', 'utf8');
console.log(`完成: 扩写 ${enriched.length} 条, 保留原文 ${dropped.length} 条${dropped.length ? ' → ' + dropped.join(', ') : ''}`);
