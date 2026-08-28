#!/usr/bin/env node
// 从真实路由评测结果拟合越界置信度的 Isotonic 校准模型。
// 用法: node scripts/fit-routing-calibration.mjs <eval-result.json> [--output <path>]

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const args = process.argv.slice(2);
const inputPath = args.find(arg => !arg.startsWith('--'));
const outputIndex = args.indexOf('--output');
const outputPath = outputIndex >= 0 && args[outputIndex + 1]
  ? args[outputIndex + 1]
  : 'backend/src/main/resources/routing/isotonic-oos-v1.json';
const allowSmall = args.includes('--allow-small');
const production = args.includes('--production');
const minSamples = Number(flagValue('--min-samples', '50'));
const version = flagValue('--version', 'routing-oos-isotonic-dev-v1');
const gateThreshold = Number(flagValue('--gate-threshold', '0.92'));

if (!inputPath) {
  console.error('缺少评测结果文件。用法: node scripts/fit-routing-calibration.mjs <eval-result.json>');
  process.exit(2);
}

function flagValue(name, fallback) {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] && !args[index + 1].startsWith('--')
    ? args[index + 1] : fallback;
}

if (!Number.isInteger(minSamples) || minSamples < 2) {
  console.error('--min-samples 必须是大于等于 2 的整数');
  process.exit(2);
}
if (!Number.isFinite(gateThreshold) || gateThreshold < 0 || gateThreshold > 1) {
  console.error('--gate-threshold 必须在 0 到 1 之间');
  process.exit(2);
}

const result = JSON.parse(readFileSync(resolve(inputPath), 'utf8'));
const samples = result.router?.calibration_samples ?? [];
const outOfScopeSamples = samples
  .filter(sample => sample?.predicted === 'out_of_scope')
  .map(sample => ({
    raw: Number(sample.confidence),
    target: sample.expected === 'out_of_scope' ? 1 : 0,
  }))
  .filter(sample => Number.isFinite(sample.raw) && sample.raw >= 0 && sample.raw <= 1);

if (outOfScopeSamples.length < minSamples && !allowSmall) {
  console.error(`越界预测样本只有 ${outOfScopeSamples.length} 条，少于安全下限 ${minSamples} 条。`);
  console.error('如仅生成开发测试模型，请显式添加 --allow-small；生产模型不能绕过该保护。');
  process.exit(3);
}
if (!outOfScopeSamples.some(sample => sample.target === 1)
    || !outOfScopeSamples.some(sample => sample.target === 0)) {
  console.error('校准集必须同时包含越界真阳性和越界误判反例。');
  process.exit(3);
}

const highConfidence = outOfScopeSamples.filter(sample => sample.raw >= gateThreshold);
const highConfidenceErrors = highConfidence.filter(sample => sample.target === 0).length;
const highConfidenceErrorRate = highConfidence.length
  ? highConfidenceErrors / highConfidence.length : 1;
if (production && (allowSmall || outOfScopeSamples.length < minSamples
    || highConfidence.length === 0 || highConfidenceErrorRate > 0.01)) {
  console.error(`生产门禁未通过: 样本=${outOfScopeSamples.length}, 高置信样本=${highConfidence.length}, `
    + `高置信误判率=${highConfidenceErrorRate.toFixed(4)}`);
  process.exit(4);
}

const blocks = fitIsotonic(outOfScopeSamples);
const points = toBoundaryPoints(blocks);
const positiveCount = outOfScopeSamples.filter(sample => sample.target === 1).length;
const artifact = {
  version,
  model_type: 'isotonic_linear_lookup',
  target: 'P(actual_scope=out_of_scope | predicted_scope=out_of_scope, raw_confidence)',
  development_only: !production,
  sample_count: outOfScopeSamples.length,
  positive_count: positiveCount,
  negative_count: outOfScopeSamples.length - positiveCount,
  gate_threshold: gateThreshold,
  high_confidence_error_rate: highConfidenceErrorRate,
  points,
};

const destination = resolve(outputPath);
mkdirSync(dirname(destination), { recursive: true });
writeFileSync(destination, JSON.stringify(artifact, null, 2) + '\n', 'utf8');
console.log(`已生成校准模型: ${destination}`);
console.log(`样本=${artifact.sample_count} 真阳性=${artifact.positive_count} 反例=${artifact.negative_count}`);
console.log(`development_only=${artifact.development_only} version=${artifact.version}`);

function fitIsotonic(samplesToFit) {
  const sorted = [...samplesToFit].sort((left, right) => left.raw - right.raw);
  const grouped = [];
  for (const sample of sorted) {
    const previous = grouped.at(-1);
    if (previous && previous.raw === sample.raw) {
      previous.sum += sample.target;
      previous.count++;
    } else {
      grouped.push({ raw: sample.raw, sum: sample.target, count: 1 });
    }
  }

  const fitted = [];
  for (const group of grouped) {
    fitted.push({
      minRaw: group.raw,
      maxRaw: group.raw,
      sum: group.sum,
      count: group.count,
      mean: group.sum / group.count,
    });
    while (fitted.length >= 2
        && fitted.at(-2).mean > fitted.at(-1).mean) {
      const right = fitted.pop();
      const left = fitted.pop();
      const merged = {
        minRaw: left.minRaw,
        maxRaw: right.maxRaw,
        sum: left.sum + right.sum,
        count: left.count + right.count,
      };
      merged.mean = merged.sum / merged.count;
      fitted.push(merged);
    }
  }
  return fitted;
}

function toBoundaryPoints(fitted) {
  if (fitted.length === 1) {
    return [
      { raw: 0, calibrated: fitted[0].mean },
      { raw: 1, calibrated: fitted[0].mean },
    ];
  }
  const points = [{ raw: 0, calibrated: fitted[0].mean }];
  for (let index = 1; index < fitted.length; index++) {
    const boundary = (fitted[index - 1].maxRaw + fitted[index].minRaw) / 2;
    if (boundary > points.at(-1).raw && boundary < 1) {
      points.push({ raw: boundary, calibrated: fitted[index].mean });
    }
  }
  const last = fitted.at(-1).mean;
  if (points.at(-1).raw < 1) points.push({ raw: 1, calibrated: last });
  return points;
}
