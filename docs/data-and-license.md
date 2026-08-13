# 数据、来源与许可证

## 1. 数据目录

| 路径 | 内容 | 发布注意 |
| --- | --- | --- |
| `graph_data/seed_skills.json` | 技能节点和前置关系 | 检查名称、描述和来源授权 |
| `graph_data/seed_resources.json` | 资源节点 | 优先保留官方或一手链接 |
| `graph_data/seed_jobs.json` | 示例岗位和要求 | 仅作为示例快照，不代表实时岗位 |
| `graph_data/source_overrides.json` | 经过核验的来源链接 | 发布前确认链接和转载条款 |
| `evals/rag_testset.json` | 项目领域 Golden Set | Gold 节点依据图谱和人工确认 |
| `evals/router_testset.json` | 意图路由标注集 | 当前为人工标注样例 |

## 2. 数据发布规则

开源前逐项检查：

1. 是否包含真实用户信息；
2. 是否包含未经授权的全文资料；
3. 是否能够从数据反推出个人身份；
4. 是否需要保留原始来源和版权说明；
5. 是否需要把数据拆成“可发布样例”和“仅本地私有数据”。

岗位数据属于时间敏感数据，仓库中的岗位内容应视为演示快照。不要把它描述成实时职位库，也不要把快照中的公司、薪资和要求当作当前事实。

## 3. 外部来源

`graph_data/source_overrides.json` 用于给资源补充经过核验的来源地址。导入脚本优先使用资源自身 `url`，其次使用覆盖表；没有核验链接的资源保持空链接，前端会明确显示未收录原始链接。

增量来源更新可以使用：

```powershell
node scripts/generate_source_updates.mjs > source_updates.sql
wsl docker exec -i tutor-postgres psql -U tutor -d tutor < source_updates.sql
```

执行前应检查 SQL 文件内容和当前数据库环境，生产库要先备份。

## 4. 许可证建议

仓库正式公开前需要补充根目录 `LICENSE`，并在 README 中明确源代码许可证、示例数据许可证、第三方模型服务限制、外部资源链接的版权归属、商业使用范围和评测集再分发规则。

在许可证明确前，贡献者可以阅读和运行仓库，但不应默认拥有所有数据和外部资源的再分发权。

