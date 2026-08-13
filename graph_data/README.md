# Graph data

这里保存可导入 Neo4j 和 PostgreSQL 的示例领域数据：技能、资源、岗位、来源覆盖和图谱关系输入。

## 文件

- `seed_skills.json`：技能、别名、难度、前置和进阶关系；
- `seed_resources.json`：资源、格式、语言、难度和教授技能；
- `seed_jobs.json`：岗位、公司、城市、要求和 JD 快照；
- `source_overrides.json`：经过核验的资源来源链接。

数据生成和导入见 `scripts/README.md`。岗位数据是演示快照，不是实时职位数据；公开前需要确认来源授权和隐私边界。

