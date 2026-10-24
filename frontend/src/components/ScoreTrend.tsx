export type TrendPoint = {
  id: string;
  /** 0-10 的场均分 */
  score: number;
  at: string;
};

type Props = {
  /** 按时间升序传入；组件内部取最近的若干场。 */
  points: TrendPoint[];
  limit?: number;
};

const LINE = '#2f4fd4';
const GRID = '#e3e1d9';
const LABEL = '#6b6a63';
const W = 360;
const H = 132;
const PAD = { top: 16, right: 16, bottom: 24, left: 28 };

function fmtDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return `${date.getMonth() + 1}/${date.getDate()}`;
}

/**
 * 跨场次分数趋势。数据来自 /interview/history（已含 avgScore/createdAt），
 * 不需要为画图新增后端接口。少于两场时不敢画折线（一条直线没有"趋势"可言），
 * 改为给出下一步该做什么。
 */
export function ScoreTrend({ points, limit = 10 }: Props) {
  const usable = points
    .filter((point) => Number.isFinite(point.score))
    .slice(-limit);

  if (usable.length < 2) {
    return (
      <div className="rounded-md border border-dashed border-ink-200 px-4 py-3 text-xs leading-[1.7] text-ink-500">
        再完成一场面试，这里就会出现分数趋势线。
      </div>
    );
  }

  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  const stepX = innerW / (usable.length - 1);
  const y = (score: number) =>
    PAD.top + innerH * (1 - Math.max(0, Math.min(10, score)) / 10);

  const coords = usable.map((point, index) => ({
    ...point,
    cx: PAD.left + stepX * index,
    cy: y(point.score),
  }));
  const first = coords[0];
  const last = coords[coords.length - 1];
  const delta = last.score - first.score;
  const showEveryLabel = coords.length <= 6;

  return (
    <div>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="h-auto w-full"
        role="img"
        aria-label={`最近 ${coords.length} 场面试的分数趋势，从 ${first.score.toFixed(1)} 到 ${last.score.toFixed(1)} 分`}
      >
        {[0, 5, 10].map((tick) => (
          <g key={tick}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(tick)} y2={y(tick)} stroke={GRID} strokeWidth={1} />
            <text x={PAD.left - 6} y={y(tick) + 3} textAnchor="end" fontSize={8} fill={LABEL}>
              {tick}
            </text>
          </g>
        ))}
        <polyline
          points={coords.map((point) => `${point.cx.toFixed(1)},${point.cy.toFixed(1)}`).join(' ')}
          fill="none"
          stroke={LINE}
          strokeWidth={2}
          strokeLinejoin="round"
          strokeLinecap="round"
        />
        {coords.map((point, index) => (
          <g key={point.id}>
            <circle cx={point.cx} cy={point.cy} r={3.2} fill={LINE} />
            {(showEveryLabel || index === 0 || index === coords.length - 1) && (
              <text
                x={point.cx}
                y={point.cy - 7}
                textAnchor={index === 0 ? 'start' : index === coords.length - 1 ? 'end' : 'middle'}
                fontSize={8.5}
                fontWeight={600}
                fill="#181817"
              >
                {point.score.toFixed(1)}
              </text>
            )}
          </g>
        ))}
        <text x={PAD.left} y={H - 8} fontSize={8} fill={LABEL}>
          {fmtDate(first.at)}
        </text>
        <text x={W - PAD.right} y={H - 8} textAnchor="end" fontSize={8} fill={LABEL}>
          {fmtDate(last.at)}
        </text>
      </svg>
      <div className="mt-1.5 text-xs text-ink-500">
        共 {coords.length} 场，首末相差{' '}
        <span className={delta >= 0 ? 'font-medium text-emerald-600' : 'font-medium text-red-600'}>
          {delta >= 0 ? '+' : ''}
          {delta.toFixed(1)}
        </span>{' '}
        分。题目按场次重新生成，趋势反映整体表现而非等值题目的比较。
      </div>
    </div>
  );
}
