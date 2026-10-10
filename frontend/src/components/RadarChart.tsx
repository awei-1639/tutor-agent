type Props = {
  dimensions: Record<string, number>;
  /** 上次同配置面试的四维均分, 有则叠加虚线对比 */
  baseline?: Record<string, number> | null;
  size?: number;
};

export const DIMENSION_LABELS: Record<string, string> = {
  technical_depth: '技术深度',
  structure: '结构与表达',
  boundary_awareness: '边界意识',
  role_fit: '岗位匹配',
};

const ORDER = ['technical_depth', 'structure', 'boundary_awareness', 'role_fit'];
const MAIN = '#2f4fd4';
const BASELINE = '#9a9a92';
const GRID = '#e3e1d9';
const AXIS_LABEL = '#6b6a63';

function orderKeys(dimensions: Record<string, number>): string[] {
  const known = ORDER.filter((key) => key in dimensions);
  const extra = Object.keys(dimensions).filter((key) => !ORDER.includes(key));
  return [...known, ...extra];
}

/** 少于 3 个维度时雷达图会退化成一条线, 改用条形图呈现。 */
function Bars({ keys, dimensions, baseline }: { keys: string[]; dimensions: Record<string, number>; baseline?: Record<string, number> | null }) {
  return (
    <div className="space-y-2.5">
      {keys.map((key) => {
        const value = dimensions[key] ?? 0;
        const base = baseline?.[key];
        return (
          <div key={key}>
            <div className="flex items-baseline justify-between text-xs text-ink-700">
              <span>{DIMENSION_LABELS[key] ?? key}</span>
              <span className="font-medium text-ink-900">
                {value.toFixed(1)}
                {base != null && <span className="ml-1.5 font-normal text-ink-400">上次 {base.toFixed(1)}</span>}
              </span>
            </div>
            <div className="mt-1 h-1.5 w-full rounded-full bg-ink-100">
              <div className="h-1.5 rounded-full" style={{ width: `${Math.max(0, Math.min(100, value * 10))}%`, background: MAIN }} />
            </div>
          </div>
        );
      })}
    </div>
  );
}

export default function RadarChart({ dimensions, baseline, size = 260 }: Props) {
  const keys = orderKeys(dimensions);
  if (keys.length === 0) return null;
  if (keys.length < 3) return <Bars keys={keys} dimensions={dimensions} baseline={baseline} />;

  const cx = size / 2;
  const cy = size / 2;
  const radius = size / 2 - 46;
  const max = 10;
  const rings = [2.5, 5, 7.5, 10];

  const point = (index: number, value: number) => {
    const angle = -Math.PI / 2 + (2 * Math.PI * index) / keys.length;
    const r = (Math.max(0, Math.min(max, value)) / max) * radius;
    return [cx + r * Math.cos(angle), cy + r * Math.sin(angle)] as const;
  };
  const polygon = (source: Record<string, number>) =>
    keys.map((key, i) => point(i, source[key] ?? 0).join(',')).join(' ');

  return (
    <svg viewBox={`0 0 ${size} ${size}`} width={size} height={size} role="img"
      aria-label={keys.map((k) => `${DIMENSION_LABELS[k] ?? k} ${(dimensions[k] ?? 0).toFixed(1)}`).join('，')}>
      {rings.map((ring) => (
        <polygon key={ring} points={polygon(Object.fromEntries(keys.map((k) => [k, ring])))}
          fill="none" stroke={GRID} strokeWidth="1" />
      ))}
      {keys.map((key, i) => {
        const [x, y] = point(i, max);
        const [lx, ly] = point(i, max * 1.16);
        const anchor = Math.abs(lx - cx) < 6 ? 'middle' : lx > cx ? 'start' : 'end';
        return (
          <g key={key}>
            <line x1={cx} y1={cy} x2={x} y2={y} stroke={GRID} strokeWidth="1" />
            <text x={lx} y={ly} textAnchor={anchor} dominantBaseline="middle" fontSize="11" fill={AXIS_LABEL}>
              {DIMENSION_LABELS[key] ?? key}
            </text>
          </g>
        );
      })}
      {baseline && Object.keys(baseline).length > 0 && (
        <polygon points={polygon(baseline)} fill="none" stroke={BASELINE} strokeWidth="1.5" strokeDasharray="4 3" />
      )}
      <polygon points={polygon(dimensions)} fill={MAIN} fillOpacity="0.16" stroke={MAIN} strokeWidth="2" strokeLinejoin="round" />
      {keys.map((key, i) => {
        const [x, y] = point(i, dimensions[key] ?? 0);
        return <circle key={key} cx={x} cy={y} r="3" fill={MAIN} />;
      })}
    </svg>
  );
}
