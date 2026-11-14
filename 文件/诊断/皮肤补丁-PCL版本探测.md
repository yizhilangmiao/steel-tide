# 皮肤补丁（CustomSkinLoader）在 PCL 上崩溃 · 排查与修复

> 2026-11-13 实锤，用户实例 `D:\mc\versions\钢铁潮汐-开发`（PCL，NeoForge 21.1.265 / MC 1.21.1）。

## 症状

启动即崩，`Description: Initializing game`，堆栈开头：

```
java.lang.IllegalStateException: Transformation rule
  'customskinloader:skin-manager-patch/skin-manager.constructor.v1'
  selected net/minecraft/client/resources/SkinManager for protocol=0, world=0
  but did not modify any bytecode.
```

崩在 `Minecraft.<init>`：KubeJS（Rhino）去读 `Minecraft` 的成员时，连带触发 `SkinManager` 的类转换。

## 根因（不是模组冲突，也不是包内容问题）

CSL 用 `ClassLoader.getSystemClassLoader().getResourceAsStream("version.json")` 判断 MC 版本，
读不到就回退到 `RealmsSharedConstants.NETWORK_PROTOCOL_VERSION`——1.21.1 里这个类已不存在，
于是版本被判为 **0**（等同 1.14 以前），挑到了错误的补丁变体，bytecode 锚点对不上，直接抛异常。

`version.json` 在哪？在原版 client jar 根目录（本机 `D:\mc\versions\1.21.1\1.21.1.jar` 内含）。
官方启动器 / Prism / HMCL 会把原版 jar 放进 `-cp`，所以能读到。

PCL 生成的 `钢铁潮汐-开发.json` 没有原版 jar 库项，`-cp` 里只有一个**不存在**的占位路径
`D:\mc\versions\钢铁潮汐-开发\钢铁潮汐-开发.jar`（NeoForge 的 client 由 FML 的 locator 提供，不走 `-cp`），
所以 CSL 一定读不到。同类问题在上游 issue 已确认（xfl03/MCCustomSkinLoader#387）。

## 修复

在 PCL 的 `-cp` 占位路径上放一个**只含 `version.json`** 的 jar（不带任何 class，等同官方 `client-extra.jar`，
并且 PCL 已经传了 `-DignoreList=client-extra,钢铁潮汐-开发.jar`，FML 不会把它当模组扫）：

```powershell
pwsh -File .\脚本\修复皮肤版本探测.ps1 -VersionDir "D:\mc\versions\钢铁潮汐-开发"
```

脚本从 `inheritsFrom`（1.21.1）的原版 jar 里取出 `version.json` 写进占位 jar，并回读校验。
输出示例：`已生成：...\钢铁潮汐-开发.jar（323 B，条目：version.json）`，`protocol_version=767 world_version=3955`。

撤销：删掉 `D:\mc\versions\钢铁潮汐-开发\钢铁潮汐-开发.jar`。

## 已验证 / 未验证

- 已用真实 JVM 验证机制：`-cp` 不挂占位 jar 时 `version.json = NOT FOUND`，挂上后读到 `protocol_version=767`。
- **未在游戏内验证**：按用户习惯不启客户端，需自己重启看皮肤。

## 给朋友（导入 .mrpack 的）

- Modrinth App / Prism / HMCL / 官方启动器：**不需要任何处理**，CSL 能读到版本。
- 用 PCL 装包：跑同一条脚本，`-VersionDir` 换成本机版本目录即可。
- 不管哪个启动器，都不要用 `-Dcustomskinloader.ignorePatchFailure=true` 繞过：游戏能开，但 SkinManager 补丁没打成，皮肤依然不显示。
