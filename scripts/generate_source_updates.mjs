// 为已导入的知识库生成“仅更新来源元数据”的 SQL，不重建 embedding、不删除 kg_chunks。
// 用法: node scripts/generate_source_updates.mjs > source_updates.sql
import { readFileSync } from 'node:fs';

const root = new URL('../', import.meta.url);
const readJson = path => JSON.parse(readFileSync(new URL(path, root), 'utf8'));
const { resources } = readJson('graph_data/seed_resources.json');
const overrides = readJson('graph_data/source_overrides.json');
const byId = new Map(resources.map(r => [r.id, r]));
const sql = value => `'${String(value).replace(/'/g, "''")}'`;

console.log('BEGIN;');
for (const [id, url] of Object.entries(overrides)) {
  const resource = byId.get(id);
  if (!resource) throw new Error(`unknown resource: ${id}`);
  if (!/^https?:\/\//.test(url)) throw new Error(`non-http(s) source URL: ${id}`);
  console.log(`UPDATE kg_chunks SET source_url=${sql(url)}, source_title=${sql(resource.title)}, retrieved_at=now() WHERE node_id=${sql(id)};`);
}
console.log('COMMIT;');
