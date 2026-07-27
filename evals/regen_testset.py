#!/usr/bin/env python3
# 重新生成 rag_testset, 避免 bash heredoc UTF-8 损坏 (mem记录坑).
# 复用 gen_testset.mjs 的逻辑, 用 Python 调用 DeepSeek 改写问法。
import json, os, urllib.request, urllib.error
from pathlib import Path

ROOT = Path(__file__).parent.parent
env = dict(l.split('=', 1) for l in (ROOT / '.env').read_text(encoding='utf-8').splitlines()
           if l and not l.startswith('#') and '=' in l)

skills = json.loads((ROOT / 'graph_data' / 'seed_skills.json').read_text(encoding='utf-8'))['skills']
resources = json.loads((ROOT / 'graph_data' / 'seed_resources.json').read_text(encoding='utf-8'))['resources']
jobs = json.loads((ROOT / 'graph_data' / 'seed_jobs.json').read_text(encoding='utf-8'))['jobs']
skill_by_id = {s['id']: s for s in skills}

# 固定种子确定性采样
import random
rng = random.Random(42)
def sample(arr, n):
    copy = list(arr); out = []
    while len(out) < n and copy:
        out.append(copy.pop(rng.randrange(len(copy))))
    return out

cases = []
# 类型1
for s in sample([x for x in skills if len(x['description']) > 20], 15):
    cases.append({
        'type': 'single_hop_skill',
        'seed_query': f'围绕技能「{s["name"]}」({s["description"]})提一个学习者会问的问题',
        'gold': [s['id']],
    })
# 类型2
for r in sample([x for x in resources if x['teaches']], 15):
    skill = skill_by_id[r['teaches'][0]]
    gold = [x['id'] for x in resources if r['teaches'][0] in x['teaches'] and x['format'] == r['format']]
    cases.append({
        'type': 'resource_rec',
        'seed_query': f'想找学习「{skill["name"]}」的{r["format"]}类资源, 提一个自然的求推荐问题',
        'gold': gold,
    })
# 类型3
for j in sample([x for x in jobs if len(x['requires']) >= 3], 10):
    cases.append({
        'type': 'job_requirement',
        'seed_query': f'想了解「{j["title"]}」(公司: {j["company"]})这类岗位需要掌握哪些技能',
        'gold': j['requires'],
    })
# 类型4
deep = [s for s in skills if s['prerequisites']
        and any((skill_by_id.get(p) or {}).get('prerequisites') for p in s['prerequisites'])]
for s in sample(deep, 10):
    hop1 = s['prerequisites']
    hop2 = sum([(skill_by_id.get(p) or {}).get('prerequisites', []) for p in hop1], [])
    cases.append({
        'type': 'multi_hop_prereq',
        'seed_query': f'零基础想最终学会「{s["name"]}」, 提一个询问需要先掌握哪些前置知识的问题',
        'gold': list(dict.fromkeys(hop1 + hop2)),
    })

def paraphrase(batch):
    req = urllib.request.Request(
        f"{env['DEEPSEEK_BASE_URL']}/chat/completions",
        data=json.dumps({
            'model': 'deepseek-chat', 'temperature': 0.8, 'max_tokens': 3000,
            'response_format': {'type': 'json_object'},
            'messages': [
                {'role': 'system', 'content':
                    '你为检索评估集生成自然的中文用户提问。根据每条指令生成一个真实学习者口吻的问题(15-40字), '
                    '口语化、可含语气词, 三分之一的问题刻意不用指令中的原词而用同义表达。'
                    '输出JSON {"queries":["问题1",...]}, 数量与指令条数一致。'},
                {'role': 'user', 'content': '\n'.join(f'{i+1}. {c["seed_query"]}' for i, c in enumerate(batch))},
            ],
        }, ensure_ascii=False).encode('utf-8'),
        headers={'Content-Type': 'application/json',
                 'Authorization': f'Bearer {env["DEEPSEEK_API_KEY"]}'},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            out = json.loads(json.loads(resp.read())['choices'][0]['message']['content'])['queries']
    except urllib.error.HTTPError as e:
        raise RuntimeError(f'HTTP {e.code}: {e.read().decode("utf-8", "replace")[:200]}')
    if not isinstance(out, list) or len(out) != len(batch):
        raise RuntimeError(f'改写数量不符: {len(out)}/{len(batch)}')
    return out

print(f'生成 {len(cases)} 条用例, LLM改写问法中...')
for i in range(0, len(cases), 10):
    batch = cases[i:i+10]
    queries = paraphrase(batch)
    for c, q in zip(batch, queries):
        c['query'] = q
        c.pop('seed_query')
    print(f'改写 {min(i+10, len(cases))}/{len(cases)}')

out = {
    'version': 1,
    'created_at': __import__('datetime').datetime.now().isoformat(),
    'note': 'gold由图结构确定, LLM仅改写问法 (Python重生成避免bash UTF-8问题)',
    'cases': [{'id': f'q{i+1:03d}', **c} for i, c in enumerate(cases)],
}
(ROOT / 'evals' / 'rag_testset.json').write_text(
    json.dumps(out, ensure_ascii=False, indent=2), encoding='utf-8')
counts = {}
for c in cases: counts[c['type']] = counts.get(c['type'], 0) + 1
print(f'DONE → evals/rag_testset.json ({len(cases)}条: ' +
      ', '.join(f'{k}={v}' for k, v in counts.items()) + ')')