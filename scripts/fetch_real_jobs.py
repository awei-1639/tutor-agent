#!/usr/bin/env python3
"""
真实岗位源 (Phase 4 V4 4.2): HN 'Who is hiring' 月榜 → jobs 表
- 拉最新 thread (Ask HN: Who is hiring?)
- 解析 top-level comments: | Position | Company | Location | ...
- 写入 jobs 表, source='hn_real' (区分种子的 'seed')
- 增量: 按 (title+company) 指纹去重
"""
import urllib.request, json, re, os, sys, hashlib
from pathlib import Path
import psycopg2

ROOT = Path(__file__).parent.parent
env = dict(l.split('=', 1) for l in (ROOT / '.env').read_text(encoding='utf-8').splitlines()
           if l and not l.startswith('#') and '=' in l)


def fetch_hn_thread():
    """拉最新 'Who is hiring' 月榜 thread ID + comments"""
    # 1) Algolia 搜最新 thread
    url = ('https://hn.algolia.com/api/v1/search?query=Who+is+hiring+right+now'
           '&tags=story&hitsPerPage=1')
    req = urllib.request.Request(url, headers={'User-Agent': 'tutor-fetch/1.0'})
    res = json.loads(urllib.request.urlopen(req, timeout=30).read())
    if not res['hits']:
        print('ERR: no HN thread found')
        return None
    story = res['hits'][0]
    story_id = story['objectID']
    print(f'HN thread: {story.get("title")} (id={story_id})')

    # 2) Algolia 拉 thread 的 top-level comments (story_text 包含岗位)
    cmt_url = f'https://hn.algolia.com/api/v1/search?tags=comment,story_{story_id}&hitsPerPage=100'
    req = urllib.request.Request(cmt_url, headers={'User-Agent': 'tutor-fetch/1.0'})
    cmts = json.loads(urllib.request.urlopen(req, timeout=30).read())
    return [c.get('comment_text', '') for c in cmts['hits']]


HN_PATTERN = re.compile(
    # HN 标准: Company | Role | Location | Salary | ...
    r'^\s*(?P<company>[^|\n]+?)\s*\|\s*'
    r'(?P<title>[^|\n]+?)\s*\|\s*'
    r'(?P<location>[^|\n]+?)\s*(?:\||\n|$)',
    re.MULTILINE
)


def parse_comments(comments):
    """提取 HN 标准格式: Company | Role | Location | ..."""
    jobs = []
    for text in comments:
        for m in HN_PATTERN.finditer(text):
            company = m.group('company').strip()
            title = m.group('title').strip()
            location = m.group('location').strip() if m.group('location') else 'Remote'
            # 简单过滤: HN 模板标题如 "Company | Role | Location | ..." 太短视为噪声
            if len(title) > 80 or len(company) > 60:
                continue
            if not title or not company:
                continue
            if any(bad in title.lower() for bad in ['[', 'http', 'edit:', 'update:']):
                continue
            jobs.append({
                'title': title,
                'company': company,
                'city': location if location and 'remote' not in location.lower() else 'Remote',
                'jd_snapshot': text[:600].replace('\n', ' '),
            })
    return jobs


def fingerprint(j):
    return hashlib.md5(f"{j['title']}|{j['company']}".lower().encode()).hexdigest()[:16]


def main():
    limit = int(os.environ.get('LIMIT', '50'))
    print(f'拉 HN 真实岗位 (上限 {limit})...')
    raw = fetch_hn_thread()
    if not raw:
        sys.exit(1)
    parsed = parse_comments(raw)
    print(f'解析候选: {len(parsed)}')

    conn = psycopg2.connect(host='localhost', port=5432, user='tutor',
                            password=env['POSTGRES_PASSWORD'], database='tutor',
                            client_encoding='utf8')
    cur = conn.cursor()
    inserted = 0; skipped = 0

    for j in parsed[:limit]:
        fp = fingerprint(j)
        node_id = f'hn:{fp}'
        cur.execute('SELECT 1 FROM jobs WHERE node_id=%s', (node_id,))
        if cur.fetchone():
            skipped += 1; continue
        cur.execute(
            'INSERT INTO jobs (node_id, title, company, city, salary, education, '
            'requires_raw, jd_snapshot, source, released, fetched_at) '
            'VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,now())',
            (node_id, j['title'], j['company'], j['city'], '', '',
             [], j['jd_snapshot'], 'hn_real', False))
        inserted += 1

    conn.commit()
    cur.execute('SELECT count(*) FROM jobs WHERE source=%s', ('hn_real',))
    total = cur.fetchone()[0]
    print(f'插入: {inserted} | 跳过(重复): {skipped} | hn_real 总数: {total}')
    cur.close(); conn.close()


if __name__ == '__main__':
    main()