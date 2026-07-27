#!/usr/bin/env python3
"""Phase 2 V4 2.2: staging_triples(approved) → Neo4j 边
Python 直连 Neo4j 避免 WSL 中文编码。
"""
import psycopg2
from neo4j import GraphDatabase
from pathlib import Path

ROOT = Path(__file__).parent.parent
env = dict(l.split('=', 1) for l in (ROOT / '.env').read_text(encoding='utf-8').splitlines()
           if l and not l.startswith('#') and '=' in l)

ALLOWED = {'PREREQUISITE', 'TEACHES', 'LEADS_TO', 'REQUIRES', 'ADVANCES_TO', 'AT_COMPANY'}
KIND_TO_LABEL = {'技能': 'Skill', '资源': 'Resource', '岗位': 'Job', '公司': 'Company'}

def parse_endpoint(s):
    if ':' not in s: return None, None
    kind, name = s.split(':', 1)
    return KIND_TO_LABEL.get(kind), name.strip()

def main():
    conn = psycopg2.connect(host='localhost', port=5432, user='tutor', password=env['POSTGRES_PASSWORD'],
                            database='tutor', client_encoding='utf8')
    cur = conn.cursor()
    cur.execute("SELECT id, head, relation, tail FROM staging_triples WHERE status='approved' ORDER BY id")
    rows = cur.fetchall()
    print(f'approved 三元组: {len(rows)} 条')

    # Neo4j: localhost:7687
    driver = GraphDatabase.driver('bolt://localhost:7687', auth=('neo4j', env['NEO4J_PASSWORD']))
    imported = 0; skipped = 0
    no_match = 0
    with driver.session() as session:
        for tid, head, rel, tail in rows:
            if rel not in ALLOWED: skipped += 1; continue
            h_label, h_name = parse_endpoint(head)
            t_label, t_name = parse_endpoint(tail)
            if not h_label or not t_label or not h_name or not t_name:
                skipped += 1; continue
            try:
                # 先 MATCH 探测端点存在性 (MERGE 静默不创建会让人误判)
                hit_h = session.run(f"MATCH (h:{h_label} {{name:$h}}) RETURN h.node_id AS id LIMIT 1", h=h_name).single()
                hit_t = session.run(f"MATCH (t:{t_label} {{name:$t}}) RETURN t.node_id AS id LIMIT 1", t=t_name).single()
                if hit_h and hit_t:
                    session.run(
                        f"MATCH (h:{h_label} {{name:$h}}) MATCH (t:{t_label} {{name:$t}}) "
                        f"MERGE (h)-[:{rel}]->(t)",
                        h=h_name, t=t_name)
                    imported += 1
                else:
                    no_match += 1
            except Exception as e:
                print(f'  ! id={tid} {head}--{rel}-->{tail} 失败: {e}')
                skipped += 1
    print(f'无端点匹配 (no_match): {no_match}')
    driver.close()
    print(f'导入: {imported} 跳过: {skipped}')

if __name__ == '__main__':
    main()