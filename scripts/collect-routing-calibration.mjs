#!/usr/bin/env node
// 只调用真实路由端点，收集校准训练所需的逐条样本，不执行 RAG 检索。
// 用法: node scripts/collect-routing-calibration.mjs [--smoke] [--output <path>]

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const BASE = process.env.ROUTER_EVAL_BASE_URL ?? 'http://localhost:8180';
const args = process.argv.slice(2);
const smoke = args.includes('--smoke');
const outputIndex = args.indexOf('--output');
const outputPath = outputIndex >= 0 && args[outputIndex + 1]
  ? args[outputIndex + 1]
  : `evals/results/router_calibration_${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
const testset = JSON.parse(readFileSync(new URL('../evals/router_testset.json', import.meta.url), 'utf8'));
const cases = smoke ? testset.cases.slice(0, 10) : testset.cases;

const samples = [];
for (const testCase of cases) {
  const response = await post('/internal/route', { question: testCase.q });
  const confidence = Math.max(0, Math.min(1, Number(response.confidence) || 0));
  samples.push({
    confidence,
    correct: response.intent === testCase.intent,
    predicted: response.intent,
    expected: testCase.intent,
  });
  process.stdout.write(`\r[router] ${samples.length}/${cases.length}`);
}
console.log();

const record = {
  at: new Date().toISOString(),
  source: 'router_testset.json',
  testset_version: testset.version,
  router: {
    calibration_samples: samples,
  },
};
const destination = resolve(outputPath);
mkdirSync(dirname(destination), { recursive: true });
writeFileSync(destination, JSON.stringify(record, null, 2) + '\n', 'utf8');
console.log(`路由校准样本已写入: ${destination}`);

async function post(path, body) {
  const response = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(90_000),
  });
  if (!response.ok) throw new Error(`${path} HTTP ${response.status}`);
  return response.json();
}
