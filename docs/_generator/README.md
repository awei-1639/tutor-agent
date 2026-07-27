# 设计文档生成器

四份 docx 由本目录脚本生成（改文档 = 改脚本重跑，双语言实现设计共享单一来源防漂移）。

```bash
# docx 模块装在用户主目录, D盘运行需指 NODE_PATH (Git Bash):
export NODE_PATH="C:/Users/lenovo/node_modules"
node gen_doc_v3.js       # → docs/个人AI学习与求职助手_最佳设计方案_V3.docx
node gen_doc_v4.js       # → docs/个人AI学习与求职助手_演进设计方案_V4无时限版.docx
node gen_impl_docs.js    # → docs/核心实现设计_*_Java版.docx + Python版.docx (共享内容单一来源)
```

变更纪律：验收标准/指标口径的修改必须在文档中标注修订日期与理由（如"口径修订2026-07-26"）。
