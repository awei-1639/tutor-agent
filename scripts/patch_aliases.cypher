// 对齐缓存 miss 的 3 个简称 → 图谱节点补别名 (实现设计 4.3 / V3 6.0)
// 仅追加, 不覆盖已有 aliases; 写完后 SkillAlignService 重跑这些 key 会命中。
MATCH (s:Skill {name: 'Python基础'}) SET s.aliases = s.aliases + ['python', 'Python'];
MATCH (s:Skill {name: '概率论与数理统计'}) SET s.aliases = s.aliases + ['概率论'];
MATCH (s:Skill {name: '深度学习基础'}) SET s.aliases = s.aliases + ['深度学习'];

// 清掉 skill_alignments 中对应 miss 缓存 (下一次对齐会重建为 exact_or_alias)
DELETE FROM skill_alignments WHERE raw_name IN ('python', '概率论', '深度学习') AND method = 'miss';