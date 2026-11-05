# mod_l61phe · 入口

> 本文件是 AI 进这个目录的唯一入口，只放「不读就会白干」的规则。

<!-- _ai-kernel:begin 由 E:\_ai\scripts\install-kernel.ps1 维护，勿手改 -->
## 协同内核（多个 AI 同目录作业，先看这段）

**开工第一件事**：`pwsh -File .agents\doctor.ps1` —— 登记项全绿再动手；有 MISSING/DRIFT 先修或先问，别在坏地基上盖楼。

| 场景 | 命令 |
|---|---|
| 体检（登记项能不能用 / 端口 / 漂移 / 版本位） | `.agents\doctor.ps1` |
| 磁盘上多出来、env.json 没登记的东西 | `.agents\drift.ps1` |
| 我要改某文件，先占位 | `.agents\lock.ps1 -Target "相对路径"` |
| 改完释放 | `.agents\lock.ps1 -Target "相对路径" -Release` |
| 装/删/改环境后登记 | `.agents\reg.ps1 -Act install -Target 名字 -Cmd "怎么装的"` |
| 历史版本只留最近两个 | `.agents\rotate.ps1 -DryRun` 然后去掉 -DryRun |
| 我是哪个会话 | `.agents\whoami.ps1` |

**四条硬规矩**
1. `env.json` 是环境唯一事实源。装东西 = 装完就 `reg.ps1` 登记 + 同步 env.json 的 `tools`/`paths`，否则下一个 AI 的 doctor 会说它缺失，或者它悄悄装了第二份。
2. 同一文件不许两个写者。写盘前 `lock.ps1` 占位，写完 `-Release`；长任务每 5 分钟续租一次。锁按「agent 身份 + 会话指纹」发放，同名不同会话不会互相续租。
3. 临时产物、中间文件、测试残留一律写到项目内的临时目录（env.json `paths.temp`），交付目录只放成品。
4. 交接写 `.agents\handoff.md`：进度 / 下一步 / 卡点，三行以内，只记结论不记过程。

- 技能怎么选、冲突听谁的 → `E:\_ai\routing.md`；谁装在哪 → `E:\_ai\skills.index.md`
- 内核升级：`pwsh -File E:\_ai\scripts\install-kernel.ps1 -Path <本项目> -Force`（只换脚本，不动 env.json / journal）
<!-- _ai-kernel:end -->
