#!/usr/bin/env python3
# 引用准确率评估 (V3 5.2 验收 / 实现设计 3.2 引用闭环):
# 流程: 测试集 → 打 /chat SSE 拿 (answer, citations) → LLM-judge 标每条引用是否合理 → 准确率
# 设计要点:
#   1. judge 看到回答+gold+evidence, 标 per-citation yes/no, 不依赖 ground-truth "应该引用谁"
#      ——忠实度反映"回答真的指向证据", 准确性反映"证据相关"
#   2. 同时输出 retrieval precision (gold 在 evidence 里的占比) 作为对照
import json, os, urllib.request, urllib.error, ssl, sys
import datetime
from pathlib import Path
import re

ROOT = Path(__file__).parent.parent
env = dict(l.split('=', 1) for l in (ROOT / '.env').read_text(encoding='utf-8').splitlines()
           if l and not l.startswith('#') and '=' in l)
BASE = 'http://localhost:8180'

JUDGE_SYS = """你是RAG引用忠实度评审。给定: 用户问题、模型回答、引用的证据列表(每条含node_id和文本)、gold标准节点列表。

判定每条引用 [S#] 是否"有对应的evidence支持":
- "yes": 回答中提到的事实/概念能在对应evidence文本中找到依据(语义匹配即可, 不需逐字一致)
- "no": 回答中提到的事实/数据在evidence中完全找不到, 或回答引用了一个不存在的evidence项

注意:
- 证据文本可能较长, 请仔细通读
- 同一个概念的不同表述(如"消息队列" vs "message queue")算匹配
- 如果回答只在evidence之外补充了常识但确实在evidence基础上展开, 仍判yes
输出JSON {"judgements":[{"sid":"S1","verdict":"yes|no","reason":"<15字>"}]}"""

def chat(question):
    """调 /chat SSE, 拼装完整回答 + 收集 citations + 解析 [S#]"""
    req = urllib.request.Request(
        f'{BASE}/chat',
        data=json.dumps({'message': question}).encode('utf-8'),
        headers={'Content-Type': 'application/json'},
    )
    full = []; cites = []; trace_id = None; conv_id = None
    with urllib.request.urlopen(req, timeout=120) as resp:
        for raw in resp:
            if not raw or raw == b'\n': continue
            line = raw.decode('utf-8', errors='replace').strip()
            if not line.startswith('data:'): continue
            payload = line[5:].strip()
            if not payload: continue
            try: evt = json.loads(payload)
            except Exception: continue
            t = evt.get('text')
            if isinstance(t, str): full.append(t)
            sid = evt.get('sid')
            if sid:
                cites.append({
                    'sid': sid,
                    'node_id': evt.get('node_id', ''),
                    'type': evt.get('type', ''),
                    'title': evt.get('title', ''),
                    'text': (evt.get('text', '') or '')[:1500],
                })
            if evt.get('trace_id'): trace_id = evt['trace_id']
            if evt.get('conversation_id'): conv_id = evt['conversation_id']
    answer = ''.join(full)
    used_sids = sorted(set(re.findall(r'\[S(\d+)\]', answer)), key=int)
    return {'answer': answer, 'citations': cites, 'used_sids': used_sids,
            'trace_id': trace_id, 'conv_id': conv_id}

def judge(question, answer, citations, used_sids, gold):
    if not used_sids:
        return {'judgements': [], 'note': 'no_citations_in_answer'}
    sub = []
    for n in used_sids:
        ev = next((c for c in citations if c['sid'] == 'S' + n), None)
        if not ev: continue
        sub.append(f"[S{n}] node={ev['node_id']}\n  text={ev['text']}")
    payload = f"""问题: {question}
回答: {answer[:2000]}

引用的证据(完整文本):
{chr(10).join(sub) if sub else '(无)'}"""
    req = urllib.request.Request(
        f"{env['DEEPSEEK_BASE_URL']}/chat/completions",
        data=json.dumps({
            'model': 'deepseek-chat', 'temperature': 0.0, 'max_tokens': 1000,
            'response_format': {'type': 'json_object'},
            'messages': [
                {'role': 'system', 'content': JUDGE_SYS},
                {'role': 'user', 'content': payload},
            ],
        }, ensure_ascii=False).encode('utf-8'),
        headers={'Content-Type': 'application/json',
                 'Authorization': f'Bearer {env["DEEPSEEK_API_KEY"]}'},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = json.loads(r.read())['choices'][0]['message']['content']
            out = json.loads(raw)
        return out
    except urllib.error.HTTPError as e:
        return {'judgements': [], 'error': f'HTTP {e.code}'}
    except Exception as e:
        return {'judgements': [], 'error': str(e), 'raw': raw[:500] if 'raw' in dir() else ''}

def main():
    cases = json.loads((ROOT / 'evals' / 'rag_testset.json').read_text(encoding='utf-8'))['cases']
    limit = int(os.environ.get('LIMIT', '0')) or len(cases)
    cases = cases[:limit]
    print(f'引用准确率评估: {len(cases)}条用例', file=sys.stderr)

    rows = []
    for i, c in enumerate(cases):
        try:
            res = chat(c['query'])
        except Exception as e:
            print(f'[{i+1}/{len(cases)}] chat失败: {e}', file=sys.stderr); continue
        evidence_ids = [ev['node_id'] for ev in res['citations']]
        gold_hits = [g for g in c['gold'] if g in evidence_ids]
        retrieval_prec = len(gold_hits) / max(len(evidence_ids), 1)
        retrieval_recall = len(gold_hits) / max(len(c['gold']), 1)
        verdicts = judge(c['query'], res['answer'], res['citations'], res['used_sids'], c['gold'])
        yes = sum(1 for j in verdicts.get('judgements', []) if j['verdict'] == 'yes')
        total = len(verdicts.get('judgements', []))
        rows.append({
            'id': c['id'], 'type': c['type'], 'gold': c['gold'],
            'used_sids': res['used_sids'],
            'evidence_ids': evidence_ids,
            'gold_in_evidence': gold_hits,
            'retrieval_precision': round(retrieval_prec, 3),
            'retrieval_recall': round(retrieval_recall, 3),
            'judgements': verdicts.get('judgements', []),
            'citation_accuracy': round(yes / total, 3) if total else None,
            'judged_count': total,
            'answer_chars': len(res['answer']),
        })
        print(f'[{i+1}/{len(cases)}] {c["id"]} used={len(res["used_sids"])} '
              f'prec={retrieval_prec:.2f} rec={retrieval_recall:.2f} '
              f'cite_acc={yes}/{total}', file=sys.stderr)
    # 聚合
    valid = [r for r in rows if r['citation_accuracy'] is not None]
    overall_acc = sum(r['citation_accuracy'] for r in valid) / len(valid) if valid else 0
    overall_prec = sum(r['retrieval_precision'] for r in rows) / len(rows) if rows else 0
    overall_recall = sum(r['retrieval_recall'] for r in rows) / len(rows) if rows else 0
    by_type = {}
    for t in sorted(set(r['type'] for r in rows)):
        sub = [r for r in rows if r['type'] == t]
        v = [r for r in sub if r['citation_accuracy'] is not None]
        by_type[t] = {
            'n': len(sub),
            'cite_acc': round(sum(r['citation_accuracy'] for r in v) / len(v), 3) if v else None,
            'cite_judged': sum(r['judged_count'] for r in sub),
            'retrieval_prec': round(sum(r['retrieval_precision'] for r in sub) / len(sub), 3),
            'retrieval_recall': round(sum(r['retrieval_recall'] for r in sub) / len(sub), 3),
        }
    summary = {
        'at': datetime.datetime.now().isoformat(),
        'cases': len(rows),
        'overall_citation_accuracy': round(overall_acc, 3),
        'overall_retrieval_precision': round(overall_prec, 3),
        'overall_retrieval_recall': round(overall_recall, 3),
        'by_type': by_type,
        'judge_model': 'deepseek-chat',
    }
    out_dir = ROOT / 'evals' / 'results'
    out_dir.mkdir(exist_ok=True)
    fname = f'cite_{summary["at"].replace(":", "-").replace(".", "-")}.json'
    (out_dir / fname).write_text(
        json.dumps({'summary': summary, 'rows': rows}, ensure_ascii=False, indent=2),
        encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f'\n结果已写 evals/results/{fname}')

if __name__ == '__main__':
    main()