#!/usr/bin/env python3
"""Phase 2 V4 2.2: LLM 三元组抽取 → staging_triples (Python 重写, 绕开 node 的 WSL 中文编码问题)
用法: python scripts/extract_triples.py [--limit N]
"""
import json, os, urllib.request, urllib.error, argparse, sys
from pathlib import Path

ROOT = Path(__file__).parent.parent
env = dict(l.split('=', 1) for l in (ROOT / '.env').read_text(encoding='utf-8').splitlines()
           if l and not l.startswith('#') and '=' in l)

ALLOWED = {'PREREQUISITE', 'TEACHES', 'LEADS_TO', 'REQUIRES', 'ADVANCES_TO', 'AT_COMPANY'}
AUTO_THRESHOLD = 0.8
REVIEW_THRESHOLD = 0.5

def extract(item):
    sys_prompt = f"""你是知识图谱三元组抽取器。从节点描述抽取 (head, relation, tail, confidence)。
relation 白名单: {', '.join(ALLOWED)}
规则:
- head/tail 用"类别:名称", 如"技能:神经网络"
- 只输出有把握的, 不确定就降低 confidence 或省略
- confidence: 直接关系≥0.8, 间接 0.5-0.8
- 输出 JSON {{"triples":[{{"head":"...","relation":"...","tail":"...","confidence":0.85}}]}}"""
    req = urllib.request.Request(
        f"{env['DEEPSEEK_BASE_URL']}/chat/completions",
        data=json.dumps({
            'model': 'deepseek-chat', 'temperature': 0.2, 'max_tokens': 1500,
            'response_format': {'type': 'json_object'},
            'messages': [
                {'role': 'system', 'content': sys_prompt},
                {'role': 'user', 'content': item['text']},
            ],
        }, ensure_ascii=False).encode('utf-8'),
        headers={'Content-Type': 'application/json',
                 'Authorization': f"Bearer {env['DEEPSEEK_API_KEY']}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            out = json.loads(json.loads(r.read())['choices'][0]['message']['content'])
        return out.get('triples', []) if isinstance(out, dict) else []
    except (urllib.error.HTTPError, json.JSONDecodeError) as e:
        return []

def load_items(limit):
    items = []
    for s in json.loads((ROOT / 'graph_data' / 'seed_skills.json').read_text(encoding='utf-8'))['skills']:
        items.append({
            'kind': 'skill', 'id': s['id'],
            'text': f"技能: {s['name']}\n别名: {'/'.join(s.get('aliases', []))}\n描述: {s['description']}"
        })
    for r in json.loads((ROOT / 'graph_data' / 'seed_resources.json').read_text(encoding='utf-8'))['resources']:
        items.append({
            'kind': 'resource', 'id': r['id'],
            'text': f"资源: {r['title']}\n形式: {r['format']}, 语言: {r['language']}\n描述: {r['description']}"
        })
    for j in json.loads((ROOT / 'graph_data' / 'seed_jobs.json').read_text(encoding='utf-8'))['jobs']:
        items.append({
            'kind': 'job', 'id': j['id'],
            'text': f"岗位: {j['title']} ({j['company']}, {j['city']})\n要求: {','.join(j.get('requires', []))}\nJD: {j.get('jd_snapshot','')}"
        })
    return items[:limit] if limit > 0 else items

# === 用 psycopg2 直连 PG, 避免 WSL 中文编码损坏 ===
import psycopg2
conn = psycopg2.connect(host='localhost', port=5432, user='tutor', password=env['POSTGRES_PASSWORD'],
                        database='tutor', client_encoding='utf8')
cur = conn.cursor()

def exec_sql(sql, params=None):
    cur.execute(sql, params or ())
    conn.commit()

def insert_triple(head, relation, tail, confidence, source, status):
    cur.execute(
        'INSERT INTO staging_triples (head, relation, tail, confidence, source, status) VALUES (%s, %s, %s, %s, %s, %s)',
        (head, relation, tail, confidence, source, status))
    conn.commit()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--limit', type=int, default=0)
    args = ap.parse_args()

    items = load_items(args.limit)
    print(f'抽取 {len(items)} 个节点, 关系白名单: {sorted(ALLOWED)}', file=sys.stderr)

    exec_sql('TRUNCATE staging_triples RESTART IDENTITY;')

    auto = 0; review = 0; dropped = 0
    for i, it in enumerate(items):
        triples = extract(it)
        for t in triples:
            if not isinstance(t, dict) or not t.get('relation'): continue
            if t['relation'] not in ALLOWED: dropped += 1; continue
            conf = t.get('confidence', 0)
            if not isinstance(conf, (int, float)) or conf < REVIEW_THRESHOLD: dropped += 1; continue
            status = 'approved' if conf >= AUTO_THRESHOLD else 'pending'
            insert_triple(str(t.get('head', '')), t['relation'], str(t.get('tail', '')),
                          conf, f"extract:{it['id']}", status)
            if status == 'approved': auto += 1
            else: review += 1
        print(f'\r[{i+1}/{len(items)}] auto={auto} review={review} dropped={dropped}', end='', file=sys.stderr)
    print(file=sys.stderr)

    cur.close(); conn.close()

    total = auto + review
    rate = auto / total * 100 if total > 0 else 0
    print(f'\nDONE: {len(items)} 节点')
    print(f'  auto_approved (≥{AUTO_THRESHOLD}, 直入图): {auto}')
    print(f'  review_pending ({REVIEW_THRESHOLD}-{AUTO_THRESHOLD}, 待人工): {review}')
    print(f'  dropped (<{REVIEW_THRESHOLD} 或关系不在白名单): {dropped}')
    print(f'  自动化率(无需人工): {rate:.1f}%')

if __name__ == '__main__':
    main()