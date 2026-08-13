import { type ReactNode, useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, toUserMessage, type EvalAggregate, type EvalBadcaseCluster, type EvalCase, type EvalModeResult, type EvalQualityGate, type EvalRunDetail, type EvalRunSummary } from '../lib/api';

const MODE_LABEL: Record<string, string> = {
  vector_only: '纯向量',
  fused: '混合检索',
  fused_rerank: '混合检索 + 重排',
  agentic: 'Agentic 多跳',
};

const TYPE_LABEL: Record<string, string> = {
  single_hop_skill: '单跳技能',
  skill_concept: '技能概念',
  resource_rec: '资源推荐',
  job_requirement: '岗位要求',
  job_breakdown: '岗位拆解',
  multi_hop_prereq: '多跳前置',
};

export default function RagEvalPage() {
  const qc = useQueryClient();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [topK, setTopK] = useState(5);
  const [limit, setLimit] = useState(50);
  const [baselineRunId, setBaselineRunId] = useState<number | null>(null);

  const runs = useQuery({
    queryKey: ['eval-runs'],
    queryFn: api.listEvalRuns,
    refetchInterval: 5000,
  });

  useEffect(() => {
    if (selectedId === null && runs.data?.length) setSelectedId(runs.data[0].id);
  }, [runs.data, selectedId]);

  const detail = useQuery({
    queryKey: ['eval-run', selectedId],
    queryFn: () => api.getEvalRun(selectedId!),
    enabled: selectedId !== null,
    refetchInterval: (query) => query.state.data?.status === 'running' ? 2000 : false,
  });
  const baseline = useQuery({
    queryKey: ['eval-run', baselineRunId],
    queryFn: () => api.getEvalRun(baselineRunId!),
    enabled: baselineRunId !== null,
  });

  const start = useMutation({
    mutationFn: () => api.startEval({ topK, limit }),
    onSuccess: (run) => {
      setSelectedId(run.id);
      void qc.invalidateQueries({ queryKey: ['eval-runs'] });
    },
  });

  const current = detail.data;
  const modes = current?.metrics?.modes ?? {};
  const modeNames = Object.keys(modes);
  const primaryMode = modeNames.includes('agentic') ? 'agentic' : (modeNames[modeNames.length - 1] ?? 'fused');
  const primary = modes[primaryMode];
  const [compareMode, setCompareMode] = useState('vector_only');
  const compare = modes[compareMode];

  return (
    <div className="h-full flex overflow-hidden bg-[#fbfcfe]">
      <HistoryPanel runs={runs.data ?? []} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 min-w-0 overflow-y-auto">
        <div className="max-w-[1440px] mx-auto px-7 py-7 lg:px-10">
          <header className="flex flex-wrap items-end justify-between gap-5 mb-7">
            <div>
              <div className="flex items-center gap-2 text-xs font-medium text-ink-500 mb-2">
                <FlaskIcon /> <span>RAG Evaluation</span>
              </div>
              <h1 className="text-[28px] leading-tight font-semibold tracking-tight text-ink-900">检索质量评测</h1>
              <p className="text-sm text-ink-500 mt-2">真实调用当前检索管线，按 Gold 节点计算 Recall、MRR，并保留每条失败用例。</p>
            </div>
            <div className="flex items-center gap-2">
              <label className="flex items-center gap-2 text-xs text-ink-500 border border-ink-200 bg-white rounded-lg px-3 py-2">
                Top K
                <select value={topK} onChange={e => setTopK(Number(e.target.value))} className="bg-transparent text-ink-800 font-medium outline-none">
                  {[3, 5, 10].map(value => <option key={value} value={value}>{value}</option>)}
                </select>
              </label>
              <label className="flex items-center gap-2 text-xs text-ink-500 border border-ink-200 bg-white rounded-lg px-3 py-2">
                用例
                <select value={limit} onChange={e => setLimit(Number(e.target.value))} className="bg-transparent text-ink-800 font-medium outline-none">
                  {[10, 50, 100, 200, 280].map(value => <option key={value} value={value}>{value}</option>)}
                </select>
              </label>
              <button
                onClick={() => start.mutate()}
                disabled={start.isPending || current?.status === 'running'}
                className="inline-flex items-center gap-2 rounded-lg bg-ink-900 px-4 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-ink-800 disabled:opacity-50"
              >
                <PlayIcon /> {start.isPending ? '创建中…' : '开始评测'}
              </button>
            </div>
          </header>

          {start.isError && <Notice tone="error">评测启动失败：{toUserMessage(start.error, '评测服务暂时不可用，请稍后重试。')}</Notice>}
          {current?.status === 'failed' && <Notice tone="error">本次评测失败：{current.error ?? '未知错误'}</Notice>}

          {!current && <EmptyState onStart={() => start.mutate()} />}
          {current && <>
            <RunHeader run={current} primaryMode={primaryMode} gate={primary?.qualityGate} />
            {current.status === 'running' && <RunningState total={current.totalCases ?? 0} />}
            {current.status === 'completed' && primary && <>
              <MetricCards aggregate={primary.overall} topK={current.topK ?? topK} />
              <QualityGate gate={primary.qualityGate} />
              <HistoricalBaseline current={current} primary={primary} primaryMode={primaryMode} runs={runs.data ?? []} baseline={baseline.data} selectedId={baselineRunId} onChange={setBaselineRunId} />
              <CompareSection modes={modes} selected={compareMode} onChange={setCompareMode} compare={compare} primary={primary} topK={current.topK ?? topK} />
              <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_330px] gap-5 mt-5">
                <SliceTable modes={modes} primaryMode={primaryMode} topK={current.topK ?? topK} />
                <RunConfig run={current} modeNames={modeNames} />
              </div>
              <BadcaseClusters clusters={primary.badcaseClusters ?? []} />
              <FailureCases mode={primary} topK={current.topK ?? topK} />
            </>}
          </>}
        </div>
      </main>
    </div>
  );
}

function HistoryPanel({ runs, selectedId, onSelect }: { runs: EvalRunSummary[]; selectedId: number | null; onSelect: (id: number) => void }) {
  return <aside className="w-[258px] shrink-0 border-r border-ink-200 bg-white hidden md:flex flex-col">
    <div className="px-4 pt-7 pb-4 flex items-start justify-between">
      <div><div className="text-sm font-semibold text-ink-900">运行历史</div><div className="text-xs text-ink-500 mt-1">最近 30 次评测</div></div>
      <span className="text-ink-400 mt-0.5"><RefreshIcon /></span>
    </div>
    <div className="px-3 pb-4 space-y-2 overflow-y-auto">
      {runs.length === 0 && <div className="rounded-lg border border-dashed border-ink-200 px-3 py-5 text-xs leading-5 text-ink-500">还没有运行记录。开始一次真实评测后，结果会自动留存在这里。</div>}
      {runs.map(run => <button key={run.id} onClick={() => onSelect(run.id)} className={`w-full text-left rounded-lg border px-3 py-3 transition ${run.id === selectedId ? 'border-ink-300 bg-ink-50 shadow-sm' : 'border-transparent hover:border-ink-200 hover:bg-ink-50/60'}`}>
        <div className="flex items-center justify-between gap-2"><span className="text-xs font-semibold text-ink-900">RAG Eval · #{run.id}</span><StatusDot status={run.status} /></div>
        <div className="text-xs text-ink-600 mt-2">{formatDate(run.createdAt)}</div>
        <div className="flex items-center justify-between text-[11px] text-ink-500 mt-1.5"><span>{run.status === 'running' ? '运行中' : run.status === 'failed' ? '失败' : '已完成'}</span><span>{run.totalCases ? `${run.totalCases} 条用例` : '—'}</span></div>
      </button>)}
    </div>
  </aside>;
}

function RunHeader({ run, primaryMode, gate }: { run: EvalRunDetail; primaryMode: string; gate?: EvalQualityGate }) {
  return <section className="bg-white rounded-xl border border-ink-200 shadow-sm mb-5">
    <div className="px-5 py-4 border-b border-ink-100 flex flex-wrap items-center justify-between gap-3">
      <div className="flex items-center gap-3"><h2 className="text-sm font-semibold text-ink-900">RAG Eval · #{run.id}</h2><StatusBadge status={run.status} /></div>
      <div className="text-xs text-ink-500">{run.startedAt ? formatDate(run.startedAt) : '—'} · {run.datasetVersion ?? 'unknown dataset'} · Top {run.topK ?? '—'}</div>
    </div>
    <div className="px-5 py-3 flex flex-wrap items-center gap-x-6 gap-y-2 text-xs text-ink-500"><span>主结果：<b className="text-ink-800">{MODE_LABEL[primaryMode] ?? primaryMode}</b></span><span>数据集：<b className="text-ink-800">{run.totalCases ?? 0} 条</b></span><span>结果来源：<b className="text-ink-800">真实调用检索管线</b></span>{gate && <span>发布门禁：<b className={gate.releaseEligible ? 'text-emerald-700' : 'text-amber-700'}>{gateLabel(gate.status)}</b></span>}</div>
  </section>;
}

function RunningState({ total }: { total: number }) {
  return <section className="bg-white border border-ink-200 rounded-xl px-6 py-8 mb-5"><div className="flex items-center gap-3"><div className="h-5 w-5 rounded-full border-2 border-accent-500 border-t-transparent animate-spin" /><div><div className="text-sm font-medium text-ink-900">正在运行真实评测</div><div className="text-xs text-ink-500 mt-1">依次调用纯向量、混合检索、重排和 Agentic 管线；完成后自动刷新结果。</div></div></div><div className="mt-5 h-1.5 rounded-full bg-ink-100 overflow-hidden"><div className="h-full w-2/5 rounded-full bg-accent-500 animate-pulse" /></div><div className="mt-2 text-[11px] text-ink-400">预计处理 {total} 条用例，Agentic 多跳会额外调用 Judge。</div></section>;
}

function MetricCards({ aggregate, topK }: { aggregate: EvalAggregate; topK: number }) {
  const cards = [
    ['Recall@' + topK, formatPct(aggregate.recallAtK), 'Gold 节点平均覆盖率', 'text-ink-900'],
    ['MRR@' + topK, aggregate.mrr.toFixed(3), '首个正确节点的排名质量', 'text-ink-900'],
    ['案例通过率', formatPct(aggregate.hitAtK), aggregate.hitAtKCi95 ? `95% CI ${formatPct(aggregate.hitAtKCi95.lower)}–${formatPct(aggregate.hitAtKCi95.upper)}` : `${aggregate.passed} / ${aggregate.n} 命中至少一个 Gold`, 'text-ink-900'],
    ['P95 延迟', formatMs(aggregate.p95Ms), `${aggregate.errors} 条执行异常`, 'text-ink-900'],
  ];
  return <div className="grid grid-cols-2 xl:grid-cols-4 gap-px bg-ink-200 border border-ink-200 rounded-xl overflow-hidden mb-5">{cards.map(([label, value, hint, tone]) => <div key={label} className="bg-white px-5 py-5"><div className="text-xs font-medium text-ink-500">{label}</div><div className={`text-[30px] font-semibold tracking-tight mt-3 ${tone}`}>{value}</div><div className="text-[11px] text-ink-500 mt-1">{hint}</div></div>)}</div>;
}

function QualityGate({ gate }: { gate?: EvalQualityGate }) {
  if (!gate) return <Notice tone="info">历史运行未记录质量门禁；重新运行后会生成 P0/P1 规则、置信区间与发布资格。</Notice>;
  const tone = gate.status === 'passed' ? 'border-emerald-200 bg-emerald-50' : gate.status === 'blocked' ? 'border-red-200 bg-red-50' : 'border-amber-200 bg-amber-50';
  return <section className={`border rounded-xl mb-5 overflow-hidden ${tone}`}>
    <div className="px-5 py-4 flex flex-wrap items-center justify-between gap-3"><div><h2 className="text-sm font-semibold text-ink-900">发布质量门禁</h2><p className="text-xs text-ink-600 mt-1">P0 阻断执行风险；P1 标记质量回归。采样评测只能诊断，不能作为发布依据。</p></div><span className="rounded-full bg-white/80 px-2.5 py-1 text-xs font-semibold text-ink-800">{gateLabel(gate.status)}</span></div>
    <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 bg-white/80 border-t border-current/10">{gate.rules.map(rule => <div key={rule.code} className="px-5 py-4 border-r border-ink-100 last:border-r-0"><div className="text-[11px] text-ink-500">{rule.level} · {rule.label}</div><div className={`text-sm font-semibold mt-1 ${rule.passed === false ? 'text-red-700' : rule.passed === true ? 'text-emerald-700' : 'text-ink-500'}`}>{!rule.applicable ? '未覆盖' : rule.passed ? '通过' : '待改进'}</div>{rule.applicable && <div className="text-[11px] text-ink-500 mt-1">{rule.comparator === 'max' ? '≤' : '≥'} {formatRuleValue(rule.threshold)} · 当前 {formatRuleValue(rule.actual)}</div>}</div>)}</div>
  </section>;
}

function HistoricalBaseline({ current, primary, primaryMode, runs, baseline, selectedId, onChange }: { current: EvalRunDetail; primary: EvalModeResult; primaryMode: string; runs: EvalRunSummary[]; baseline?: EvalRunDetail; selectedId: number | null; onChange: (id: number | null) => void }) {
  const candidates = runs.filter(run => run.id !== current.id && run.status === 'completed');
  const baselineMode = baseline?.metrics?.modes?.[primaryMode];
  const comparable = !!baseline && !!baselineMode && baseline.datasetVersion === current.datasetVersion && baseline.topK === current.topK && baseline.totalCases === current.totalCases;
  return <section className="bg-white border border-ink-200 rounded-xl mb-5 overflow-hidden"><div className="px-5 py-4 border-b border-ink-100 flex flex-wrap items-center justify-between gap-3"><div><h2 className="text-sm font-semibold text-ink-900">历史基线对比</h2><p className="text-xs text-ink-500 mt-1">仅在数据集版本、Top-K、用例规模和检索模式一致时比较，避免把采样或 Top-K 变化误判为能力提升。</p></div><select value={selectedId ?? ''} onChange={e => onChange(e.target.value ? Number(e.target.value) : null)} className="border border-ink-200 rounded-md bg-white text-xs text-ink-800 px-2 py-1.5 outline-none"><option value="">选择历史运行…</option>{candidates.map(run => <option key={run.id} value={run.id}>#{run.id} · Top {run.topK} · {run.totalCases} 条</option>)}</select></div>{!baseline && <div className="px-5 py-5 text-xs text-ink-500">选择一个已完成运行，查看严格可比性检查与指标变化。</div>}{baseline && !comparable && <div className="px-5 py-5 text-xs text-amber-700 bg-amber-50">当前运行与 #{baseline.id} 不可直接比较：需保持数据集版本、Top-K、用例规模和检索模式一致。</div>}{baseline && comparable && <div className="grid grid-cols-3 divide-x divide-ink-100"><CompareMetric label={`Recall@${current.topK}`} base={baselineMode.overall.recallAtK} target={primary.overall.recallAtK} /><CompareMetric label="MRR" base={baselineMode.overall.mrr} target={primary.overall.mrr} /><CompareMetric label="案例通过率" base={baselineMode.overall.hitAtK} target={primary.overall.hitAtK} /></div>}</section>;
}

function CompareSection({ modes, selected, onChange, compare, primary, topK }: { modes: Record<string, EvalModeResult>; selected: string; onChange: (value: string) => void; compare?: EvalModeResult; primary: EvalModeResult; topK: number }) {
  return <section className="bg-white border border-ink-200 rounded-xl mb-5 overflow-hidden"><div className="px-5 py-4 border-b border-ink-100 flex flex-wrap items-center justify-between gap-3"><div><h2 className="text-sm font-semibold text-ink-900">策略内对比</h2><p className="text-xs text-ink-500 mt-1">同一批用例下比较不同检索策略；版本对比请使用上方历史基线。</p></div><label className="flex items-center gap-2 text-xs text-ink-500">对比策略<select value={selected} onChange={e => onChange(e.target.value)} className="border border-ink-200 rounded-md bg-white text-ink-800 px-2 py-1.5 outline-none">{Object.keys(modes).map(mode => <option key={mode} value={mode}>{MODE_LABEL[mode] ?? mode}</option>)}</select></label></div><div className="grid grid-cols-3 divide-x divide-ink-100"><CompareMetric label={`Recall@${topK}`} base={compare?.overall.recallAtK} target={primary.overall.recallAtK} /><CompareMetric label="MRR" base={compare?.overall.mrr} target={primary.overall.mrr} /><CompareMetric label="P95 延迟" base={compare?.overall.p95Ms} target={primary.overall.p95Ms} latency /></div></section>;
}

function CompareMetric({ label, base, target, latency }: { label: string; base?: number; target: number; latency?: boolean }) {
  if (base == null) return <div className="px-5 py-5 text-xs text-ink-400">暂无基线</div>;
  const delta = target - base;
  const good = latency ? delta <= 0 : delta >= 0;
  const value = latency ? `${delta >= 0 ? '+' : ''}${Math.round(delta)} ms` : `${delta >= 0 ? '+' : ''}${(delta * 100).toFixed(1)} pp`;
  return <div className="px-5 py-5"><div className="text-xs text-ink-500">{label}</div><div className="flex items-end justify-between gap-2 mt-3"><div><span className="text-xl font-semibold text-ink-900">{latency ? formatMs(target) : label === 'MRR' ? target.toFixed(3) : formatPct(target)}</span><span className="text-xs text-ink-400 ml-2">vs {latency ? formatMs(base) : label === 'MRR' ? base.toFixed(3) : formatPct(base)}</span></div><span className={`text-xs font-medium ${good ? 'text-emerald-600' : 'text-amber-600'}`}>{value}</span></div></div>;
}

function SliceTable({ modes, primaryMode, topK }: { modes: Record<string, EvalModeResult>; primaryMode: string; topK: number }) {
  const types = Object.keys(modes[primaryMode]?.byType ?? {});
  return <section className="bg-white border border-ink-200 rounded-xl overflow-hidden"><div className="px-5 py-4 border-b border-ink-100"><h2 className="text-sm font-semibold text-ink-900">分类成绩</h2><p className="text-xs text-ink-500 mt-1">按查询类型切片，定位总体平均值掩盖的弱项。</p></div><div className="overflow-x-auto"><table className="w-full text-left text-xs"><thead className="bg-ink-50 text-ink-500"><tr><th className="px-5 py-3 font-medium">类别</th><th className="px-3 py-3 font-medium">通过</th><th className="px-3 py-3 font-medium">Recall@{topK}</th><th className="px-3 py-3 font-medium">MRR</th><th className="px-3 py-3 font-medium">P95</th></tr></thead><tbody className="divide-y divide-ink-100">{types.map(type => { const a = modes[primaryMode].byType[type]; return <tr key={type} className="hover:bg-ink-50/50"><td className="px-5 py-3.5 font-medium text-ink-800">{TYPE_LABEL[type] ?? type}</td><td className="px-3 py-3.5 text-ink-700">{a.passed}/{a.n}</td><td className="px-3 py-3.5 font-semibold text-ink-900">{formatPct(a.recallAtK)}</td><td className="px-3 py-3.5 text-ink-700">{a.mrr.toFixed(3)}</td><td className="px-3 py-3.5 text-ink-700">{formatMs(a.p95Ms)}</td></tr>; })}</tbody></table></div></section>;
}

function RunConfig({ run, modeNames }: { run: EvalRunDetail; modeNames: string[] }) {
  return <section className="bg-white border border-ink-200 rounded-xl p-5"><h2 className="text-sm font-semibold text-ink-900">运行配置</h2><dl className="mt-4 space-y-3 text-xs"><ConfigRow label="Embedding" value="由当前服务配置" /><ConfigRow label="Dataset" value={run.datasetVersion ?? '—'} /><ConfigRow label="Top K" value={String(run.topK ?? '—')} /><ConfigRow label="用例规模" value={String(run.totalCases ?? '—')} /><ConfigRow label="评测模式" value={modeNames.map(mode => MODE_LABEL[mode] ?? mode).join(' / ')} /><ConfigRow label="数据来源" value="Gold 节点 + 真实检索结果" /></dl></section>;
}

function BadcaseClusters({ clusters }: { clusters: EvalBadcaseCluster[] }) {
  if (clusters.length === 0) return <section className="bg-emerald-50 border border-emerald-200 rounded-xl mt-5 px-5 py-4 text-sm text-emerald-800">未发现失败或部分覆盖的 Badcase 聚类。</section>;
  return <section className="bg-white border border-ink-200 rounded-xl mt-5 overflow-hidden"><div className="px-5 py-4 border-b border-ink-100"><h2 className="text-sm font-semibold text-ink-900">Badcase 根因聚类与行动项</h2><p className="text-xs text-ink-500 mt-1">确定性规则先归因；同类问题合并为可分派的修复任务，而不是只罗列失败案例。</p></div><div className="divide-y divide-ink-100">{clusters.map(cluster => <div key={cluster.code} className="px-5 py-4 grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_130px_88px] gap-3 items-start"><div><div className="flex flex-wrap items-center gap-2"><span className={`text-[11px] px-2 py-0.5 rounded-full ${cluster.severity === 'P0' ? 'bg-red-50 text-red-700' : cluster.severity === 'P1' ? 'bg-amber-50 text-amber-700' : 'bg-ink-100 text-ink-600'}`}>{cluster.severity}</span><span className="text-sm font-medium text-ink-900">{cluster.label}</span></div><p className="text-xs text-ink-600 mt-2">{cluster.suggestion}</p><p className="text-[11px] text-ink-400 mt-1.5">示例：{cluster.sampleCaseIds.join('、')}</p></div><div className="text-xs text-ink-600">Owner<br /><b className="text-ink-900">{cluster.owner}</b></div><div className="text-right"><div className="text-xl font-semibold text-ink-900">{cluster.count}</div><div className="text-[11px] text-ink-500">条用例</div></div></div>)}</div></section>;
}

function FailureCases({ mode, topK }: { mode: EvalModeResult; topK: number }) {
  const failures = useMemo(() => mode.cases.filter(c => !c.hit || c.error || c.recall < 1), [mode.cases]);
  return <section className="bg-white border border-ink-200 rounded-xl mt-5 overflow-hidden"><div className="px-5 py-4 border-b border-ink-100 flex items-center justify-between gap-3"><div><h2 className="text-sm font-semibold text-ink-900">失败与部分命中</h2><p className="text-xs text-ink-500 mt-1">当前主结果：{MODE_LABEL[mode.mode] ?? mode.mode}，Top {topK} · 共 {failures.length} 条待分析</p></div><span className="text-xs text-ink-500">真实 case 明细</span></div>{failures.length === 0 ? <div className="px-5 py-8 text-sm text-emerald-700">全部 Gold 节点均已覆盖，没有失败或部分命中用例。</div> : <div className="divide-y divide-ink-100">{failures.slice(0, 50).map(c => <FailureRow key={c.id} item={c} />)}</div>}</section>;
}

function FailureRow({ item }: { item: EvalCase }) {
  return <details className="group px-5 py-3.5"><summary className="cursor-pointer list-none flex items-start gap-3"><span className={`mt-0.5 h-2 w-2 rounded-full shrink-0 ${item.error ? 'bg-red-500' : item.hit ? 'bg-amber-400' : 'bg-red-500'}`} /><span className="min-w-0 flex-1"><span className="text-sm text-ink-800 leading-5">{item.query}</span><span className="block text-[11px] text-ink-500 mt-1">{TYPE_LABEL[item.type] ?? item.type} · Recall {formatPct(item.recall)} · {formatMs(item.latencyMs)}{item.diagnosis ? ` · ${item.diagnosis.label}` : ''}</span></span><span className="text-xs text-ink-400 group-open:rotate-90 transition">›</span></summary><div className="ml-5 mt-3 rounded-lg bg-ink-50 px-3 py-3 text-xs text-ink-600 space-y-2"><div><b className="text-ink-800">Gold：</b>{item.gold.join(', ') || '—'}</div><div><b className="text-ink-800">命中：</b>{item.hits.join(', ') || '无'}</div><div><b className="text-ink-800">Top-K：</b>{item.retrieved.join(', ') || '无'}</div>{item.diagnosis && <div><b className="text-ink-800">归因：</b>{item.diagnosis.label}（{item.diagnosis.owner}）<span className="block mt-1 text-ink-500">建议：{item.diagnosis.suggestion}</span></div>}{item.error && <div className="text-red-600"><b>错误：</b>{item.error}</div>}</div></details>;
}

function EmptyState({ onStart }: { onStart: () => void }) {
  return <section className="bg-white border border-dashed border-ink-300 rounded-xl px-6 py-16 text-center"><FlaskIcon large /><h2 className="text-base font-semibold text-ink-900 mt-4">尚未运行 RAG 评测</h2><p className="text-sm text-ink-500 mt-2">点击开始评测，系统将调用真实检索管线并保存每条 Gold 对比结果。</p><button onClick={onStart} className="mt-5 rounded-lg bg-ink-900 px-4 py-2 text-sm font-medium text-white">开始第一次评测</button></section>;
}

function Notice({ children, tone }: { children: ReactNode; tone: 'error' | 'info' }) { return <div className={`mb-5 rounded-lg border px-4 py-3 text-sm ${tone === 'error' ? 'border-red-200 bg-red-50 text-red-700' : 'border-blue-200 bg-blue-50 text-blue-700'}`}>{children}</div>; }
function ConfigRow({ label, value }: { label: string; value: string }) { return <div className="flex items-start justify-between gap-3"><dt className="text-ink-500">{label}</dt><dd className="text-right text-ink-800 font-medium max-w-[190px] break-words">{value}</dd></div>; }
function StatusDot({ status }: { status: string }) { return <span className={`h-2 w-2 rounded-full ${status === 'completed' ? 'bg-emerald-500' : status === 'failed' ? 'bg-red-500' : 'bg-amber-400 animate-pulse'}`} />; }
function StatusBadge({ status }: { status: string }) { return <span className={`text-[11px] px-2 py-1 rounded-full ${status === 'completed' ? 'text-emerald-700 bg-emerald-50' : status === 'failed' ? 'text-red-700 bg-red-50' : 'text-amber-700 bg-amber-50'}`}>{status === 'completed' ? '已完成' : status === 'failed' ? '运行失败' : '运行中'}</span>; }
function formatPct(value: number) { return `${(value * 100).toFixed(1)}%`; }
function formatMs(value: number) { return `${Math.round(value)} ms`; }
function formatRuleValue(value?: number | null) { return value == null ? '—' : value <= 1 ? formatPct(value) : String(Math.round(value)); }
function gateLabel(status: string) { return ({ passed: '可发布', needs_review: '需复核', blocked: '已阻断', sample_only: '仅采样诊断' } as Record<string, string>)[status] ?? status; }
function formatDate(value?: string) { if (!value) return '—'; const date = new Date(value); return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date); }
function FlaskIcon({ large = false }: { large?: boolean }) { return <svg className={large ? 'mx-auto h-9 w-9 text-ink-300' : 'h-4 w-4'} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M9 3h6M10 3v4l-4.5 8.5A2 2 0 0 0 7.2 18h9.6a2 2 0 0 0 1.7-2.5L14 7V3M8 13h8" /></svg>; }
function PlayIcon() { return <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor"><path d="m8 5 11 7-11 7V5Z" /></svg>; }
function RefreshIcon() { return <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M20 11a8 8 0 1 0 1 4" /><path d="M20 4v7h-7" /></svg>; }
