// 种子数据导入准备: graph_data/*.json → seed.cypher + kg_chunks.sql + jobs.sql
// 用法: node scripts/import_seed.mjs   (派生文件写入 OUT_DIR, 默认系统临时目录)
// 之后执行:
//   wsl docker exec -i tutor-neo4j cypher-shell -u neo4j -p <pwd> < seed.cypher
//   wsl docker exec -i tutor-postgres psql -U tutor -d tutor -q < kg_chunks.sql
//   wsl docker exec -i tutor-postgres psql -U tutor -d tutor -q < jobs.sql
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const env = Object.fromEntries(
  readFileSync(new URL('../.env', import.meta.url), 'utf8')
    .split(/\r?\n/).filter(l => l.includes('=') && !l.startsWith('#'))
    .map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()])
);
const OUT = process.env.OUT_DIR || join(tmpdir(), 'tutor-seed');
mkdirSync(OUT, { recursive: true });

const load = f => JSON.parse(readFileSync(new URL(`../graph_data/${f}`, import.meta.url), 'utf8'));
const { skills } = load('seed_skills.json');
const { resources } = load('seed_resources.json');
const { jobs } = load('seed_jobs.json');
const sourceOverrides = load('source_overrides.json');
const skillById = new Map(skills.map(s => [s.id, s]));

for (const [id, url] of Object.entries(sourceOverrides)) {
  if (!resources.some(r => r.id === id)) throw new Error(`source override has unknown resource: ${id}`);
  if (!/^https?:\/\//.test(url)) throw new Error(`source override must be http(s): ${id}`);
}

const esc = s => String(s ?? '').replace(/\\/g, '\\\\').replace(/'/g, "\\'");
const sqlEsc = s => String(s ?? '').replace(/'/g, "''");

// ============ 1. Cypher ============
let cy = `CREATE CONSTRAINT skill_id IF NOT EXISTS FOR (n:Skill) REQUIRE n.node_id IS UNIQUE;
CREATE CONSTRAINT res_id IF NOT EXISTS FOR (n:Resource) REQUIRE n.node_id IS UNIQUE;
CREATE CONSTRAINT job_id IF NOT EXISTS FOR (n:Job) REQUIRE n.node_id IS UNIQUE;
CREATE CONSTRAINT company_id IF NOT EXISTS FOR (n:Company) REQUIRE n.node_id IS UNIQUE;
MATCH (n:Seed) DETACH DELETE n;
`;
for (const s of skills) {
  cy += `MERGE (n:Seed:Skill {node_id:'${s.id}'}) SET n.name='${esc(s.name)}', n.aliases=[${s.aliases.map(a => `'${esc(a)}'`).join(',')}], n.description='${esc(s.description)}', n.difficulty='${s.difficulty}', n.est_hours=${s.est_hours};\n`;
}
for (const r of resources) {
  cy += `MERGE (n:Seed:Resource {node_id:'${r.id}'}) SET n.title='${esc(r.title)}', n.description='${esc(r.description)}', n.format='${r.format}', n.language='${r.language}', n.duration_hours=${r.duration_hours}, n.difficulty='${r.difficulty}';\n`;
}
const companies = new Map();
for (const j of jobs) {
  const cid = 'company:' + j.company.toLowerCase().replace(/[^a-z0-9一-龥]+/g, '-').slice(0, 40);
  companies.set(cid, j.company);
  j._cid = cid;
  cy += `MERGE (n:Seed:Job {node_id:'${j.id}'}) SET n.title='${esc(j.title)}', n.company='${esc(j.company)}', n.city='${j.city}', n.salary='${esc(j.salary)}', n.education='${esc(j.education)}', n.jd_snapshot='${esc(j.jd_snapshot)}', n.fetched_at=date();\n`;
}
for (const [cid, name] of companies) {
  cy += `MERGE (n:Seed:Company {node_id:'${cid}'}) SET n.name='${esc(name)}';\n`;
}
// 边: 方向语义见 experiments/README.md Spike3 结论
for (const s of skills) {
  for (const p of s.prerequisites || []) cy += `MATCH (a:Skill {node_id:'${p}'}),(b:Skill {node_id:'${s.id}'}) MERGE (a)-[:PREREQUISITE]->(b);\n`;
  for (const t of s.advances_to || []) cy += `MATCH (a:Skill {node_id:'${s.id}'}),(b:Skill {node_id:'${t}'}) MERGE (a)-[:ADVANCES_TO]->(b);\n`;
}
for (const r of resources) for (const t of r.teaches) {
  cy += `MATCH (a:Resource {node_id:'${r.id}'}),(b:Skill {node_id:'${t}'}) MERGE (a)-[:TEACHES]->(b);\n`;
}
for (const j of jobs) {
  j.requires.forEach((t, i) => {
    cy += `MATCH (a:Job {node_id:'${j.id}'}),(b:Skill {node_id:'${t}'}) MERGE (a)-[:REQUIRES]->(b);\n`;
    if (i === 0) cy += `MATCH (a:Skill {node_id:'${t}'}),(b:Job {node_id:'${j.id}'}) MERGE (a)-[:LEADS_TO]->(b);\n`; // 首要技能→岗位
  });
  cy += `MATCH (a:Job {node_id:'${j.id}'}),(b:Company {node_id:'${j._cid}'}) MERGE (a)-[:AT_COMPANY]->(b);\n`;
}
writeFileSync(join(OUT, 'seed.cypher'), cy);
console.log(`seed.cypher: ${skills.length} skills, ${resources.length} resources, ${jobs.length} jobs, ${companies.size} companies`);

// ============ 2. chunk 文本 (模板: 类型|名称|关键属性|一跳关系摘要) ============
const names = ids => ids.map(i => skillById.get(i)?.name || i).join(',');
const chunks = [];
for (const s of skills) {
  const leads = jobs.filter(j => j.requires[0] === s.id).slice(0, 3).map(j => j.title).join(',');
  chunks.push([s.id, 'skill', `skill|${s.name}|别名:${s.aliases.join('/')}|难度:${s.difficulty},约${s.est_hours}小时|${s.description}|前置:${names(s.prerequisites || []) || '无'} 进阶:${names(s.advances_to || []) || '无'}${leads ? ' 通往:' + leads : ''}`, null, s.name]);
}
for (const r of resources) {
  const sourceUrl = r.url || sourceOverrides[r.id] || null;
  chunks.push([r.id, 'resource', `resource|${r.title}|${r.format},${r.language},约${r.duration_hours}小时,难度:${r.difficulty}|${r.description}|教授:${names(r.teaches)}`, sourceUrl, r.title]);
}
for (const j of jobs) {
  chunks.push([j.id, 'job', `job|${j.title}-${j.company}(${j.city})|薪资:${j.salary},学历:${j.education}|要求:${names(j.requires)}|${j.jd_snapshot}`, null, `${j.title} - ${j.company}`]);
}

// ============ 3. embedding ============
async function embed(texts) {
  const res = await fetch(`${env.SILICONFLOW_BASE_URL}/embeddings`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${env.SILICONFLOW_API_KEY}` },
    body: JSON.stringify({ model: 'BAAI/bge-m3', input: texts }),
    signal: AbortSignal.timeout(120_000),
  });
  if (!res.ok) throw new Error(`embed HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return (await res.json()).data.sort((a, b) => a.index - b.index).map(d => d.embedding);
}
const vecs = [];
for (let i = 0; i < chunks.length; i += 32) {
  vecs.push(...await embed(chunks.slice(i, i + 32).map(c => c[2])));
  process.stdout.write(`\rembedding ${Math.min(i + 32, chunks.length)}/${chunks.length}`);
}
console.log();

// ============ 4. SQL ============
let kgSql = 'TRUNCATE kg_chunks;\n';
chunks.forEach((c, i) => {
  const sourceUrl = c[3] ? `'${sqlEsc(c[3])}'` : 'NULL';
  const sourceStatus = c[3] ? 'unverified' : 'missing';
  kgSql += `INSERT INTO kg_chunks (node_id, node_type, chunk_text, embedding, source_url, source_title, source_status, content_hash, retrieved_at) VALUES ('${c[0]}', '${c[1]}', '${sqlEsc(c[2])}', '[${vecs[i].join(',')}]', ${sourceUrl}, '${sqlEsc(c[4])}', '${sourceStatus}', encode(digest(convert_to('${sqlEsc(c[2])}', 'UTF8'), 'sha256'), 'hex'), now());\n`;
});
writeFileSync(join(OUT, 'kg_chunks.sql'), kgSql);

const jobVec = new Map(chunks.map((c, i) => [c[0], vecs[i]]));
let jobSql = 'TRUNCATE jobs CASCADE;\n';
jobs.forEach((j, i) => {
  const released = i % 2 === 0; // 一半直接释放, 一半进注水池 (V3 6.x Mock投放)
  jobSql += `INSERT INTO jobs (node_id, title, company, city, salary, education, requires_raw, jd_snapshot, embedding, source, released, fetched_at) VALUES ('${j.id}', '${sqlEsc(j.title)}', '${sqlEsc(j.company)}', '${sqlEsc(j.city)}', '${sqlEsc(j.salary)}', '${sqlEsc(j.education)}', ARRAY[${j.requires.map(t => `'${sqlEsc(t)}'`).join(',')}], '${sqlEsc(j.jd_snapshot)}', '[${jobVec.get(j.id).join(',')}]', 'seed', ${released}, now());\n`;
});
writeFileSync(join(OUT, 'jobs.sql'), jobSql);
console.log(`OUT_DIR=${OUT}\nDONE: seed.cypher / kg_chunks.sql / jobs.sql`);
