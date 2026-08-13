// Replay recorded interview scores against the human-calibration gate.
// Usage:
//   node evals/run_interview_score_eval.mjs --input ./interview_score_gold.json --ci
// The input is a ReplayRequest JSON object (datasetVersion + cases) or a cases array.
// This command never calls the model; it only evaluates recorded model output.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { execSync } from 'node:child_process';
import { resolve } from 'node:path';

const args = process.argv.slice(2);
const BASE = process.env.INTERVIEW_EVAL_BASE_URL ?? 'http://localhost:8180';
const CI_MODE = args.includes('--ci');
const inputPath = valueAfter('--input');
const datasetVersion = valueAfter('--dataset-version');
const minReviewers = Number(valueAfter('--min-reviewers') ?? 2);
const outputPath = valueAfter('--output');

if (args.includes('--help') || args.includes('-h')) {
  console.log('用法: node evals/run_interview_score_eval.mjs --input <json> [--ci] [--min-reviewers 2]');
  process.exit(0);
}
if (!inputPath) fail('缺少 --input；不要把未脱敏回答直接提交到评测集。');
if (!Number.isInteger(minReviewers) || minReviewers < 2 || minReviewers > 5) {
  fail('--min-reviewers 必须是 2 到 5 的整数。');
}

const source = resolve(inputPath);
let raw;
try {
  raw = JSON.parse(readFileSync(source, 'utf8'));
} catch (error) {
  fail(`无法读取评测输入 ${source}: ${error.message}`);
}

const rawObject = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
const request = Array.isArray(raw)
  ? { datasetVersion: datasetVersion ?? 'human-gold-cli', cases: raw }
  : { datasetVersion: datasetVersion ?? rawObject.datasetVersion ?? 'human-gold-cli', cases: rawObject.cases };
if (!Array.isArray(request.cases) || request.cases.length === 0) fail('评测输入至少需要一条 cases。');
if (request.cases.some(item => !item || !Number.isInteger(item.reviewerCount)
  || item.reviewerCount < minReviewers || !Number.isInteger(item.humanScoreSpread)
  || item.humanScoreSpread < 0)) {
  fail(`所有样本都必须达到至少 ${minReviewers} 名评审；请先完成双人标注。`);
}

const result = await post('/internal/interview-evals/replay', request);
const metrics = result.metrics ?? {};
const record = {
  at: new Date().toISOString(),
  gitSha: gitSha(),
  source: source,
  datasetVersion: request.datasetVersion,
  minReviewers,
  ci: CI_MODE,
  result,
};

console.log(`面试评分 replay: ${request.datasetVersion} · ${metrics.n ?? request.cases.length} 条样本`);
console.log(`MAE ${format(metrics.mae)} · 三级一致率 ${percent(metrics.gradeAgreement)} · 双人覆盖率 ${percent(metrics.doubleLabelCoverage)}`);
console.log(`评审分歧率 ${percent(metrics.reviewerDisagreementRate)} · 高置信大误差率 ${percent(metrics.highConfidenceErrorRate)}`);
for (const rule of metrics.rules ?? []) {
  console.log(`${rule.passed ? '✅' : '❌'} ${rule.label}: ${format(rule.actual)} ${rule.comparator} ${format(rule.threshold)}`);
}

const target = outputPath
  ? resolve(outputPath)
  : resolve('evals/results', `interview_score_${record.at.replace(/[:.]/g, '-')}.json`);
mkdirSync(resolve(target, '..'), { recursive: true });
writeFileSync(target, JSON.stringify(record, null, 2));
console.log(`结果已写入 ${target}`);

if (CI_MODE && metrics.releaseEligible !== true) {
  console.error('评分发布门禁未通过，CI 阻断。');
  process.exitCode = 1;
}

async function post(path, body) {
  let response;
  try {
    response = await fetch(BASE + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(90_000),
    });
  } catch (error) {
    fail(`无法连接 ${BASE}: ${error.message}`);
  }
  const text = await response.text();
  let bodyJson;
  try { bodyJson = text ? JSON.parse(text) : {}; } catch { bodyJson = {}; }
  if (!response.ok) fail(`${path} HTTP ${response.status}: ${bodyJson.message ?? bodyJson.detail ?? text}`);
  return bodyJson;
}

function valueAfter(flag) {
  const index = args.indexOf(flag);
  return index >= 0 ? args[index + 1] : undefined;
}

function fail(message) {
  console.error(`错误: ${message}`);
  process.exit(2);
}

function percent(value) {
  return Number.isFinite(value) ? `${(value * 100).toFixed(1)}%` : 'n/a';
}

function format(value) {
  return Number.isFinite(value) ? Number(value).toFixed(3) : 'n/a';
}

function gitSha() {
  try { return execSync('git rev-parse HEAD', { cwd: resolve('.') }).toString().trim(); } catch { return null; }
}
