# 泥石版本与发布规范

本文规定 `nishi` 仓库的版本号、构建变体和 GitHub Release 发布流程。所有提交到 `main` 的正式版本都必须遵守本文。

## 1. 版本号格式

正式版本使用三段式：

```text
MAJOR.MINOR.PATCH
```

例如：

```text
1.0.2
```

- `MAJOR`：发生不兼容变更时递增，例如数据结构、账号体系或主要功能入口发生不可兼容调整。
- `MINOR`：新增向后兼容的用户功能时递增，例如新增「绑定房间」。
- `PATCH`：修复缺陷、调整文案、优化性能或做不改变功能范围的小改动时递增。

预发布和测试版本只能使用后缀：

```text
1.0.2-test
1.1.0-rc.1
```

正式 Release 不使用 `-test`、`-rc` 等后缀。

## 2. versionCode 规则

`app/build.gradle.kts` 中的 `versionCode` 必须满足：

- 每次对外发布必须递增。
- 已发布过的 `versionCode` 不得重复使用。
- 不跟随 `versionName` 自动计算，避免回退或冲突。
- 当前 `1.0.4` 使用 `versionCode = 48`；下一次正式发布从 `49` 开始。

每次发布必须同时修改：

```kotlin
versionCode = 46
versionName = "1.0.2"
```

只改其中一个字段的提交不允许发布。

## 3. 构建变体

| 变体 | applicationId | 应用名 | 用途 |
| --- | --- | --- | --- |
| `release` | `com.fengkao.timetable` | 泥石 | 正式主版本，唯一允许发布到 Release |
| `qa` | `com.fengkao.timetable.testrepo` | 泥石 Test | 测试包，可和主版本共存，不发布为主版本 |
| `demo` | `com.fengkao.timetable.demo` | 泥石 Demo | 离线演示，不发布为正式版本 |

测试包必须满足：

- `versionName` 带 `-test` 后缀。
- 使用独立的 `applicationId`。
- 不得覆盖或替代 `com.fengkao.timetable` 主版本。

## 4. Git 标签与 GitHub Release

正式版本必须使用与版本号一致的 annotated tag：

```text
v1.0.2
```

发布流程：

1. 在 `main` 上完成版本号和 `versionCode` 修改。
2. 本地构建并验证 Release APK。
3. 提交版本变更，例如：

   ```text
   chore: release 1.0.2
   ```

4. 创建带注释的标签：

   ```powershell
   git tag -a v1.0.2 -m "泥石 v1.0.2"
   git push origin main
   git push origin v1.0.2
   ```

5. 创建 GitHub Release：
   - Tag：`v1.0.2`
   - 标题：`泥石 v1.0.2`
   - 附件：`nishi-1.0.2-release.apk`

已发布的 tag 不得移动、覆盖或重新指向其他提交；需要修复时发布新的 `PATCH` 版本。

## 5. 发布前检查清单

- [ ] `versionName` 和 `versionCode` 已在同一提交中更新
- [ ] `:app:testDebugUnitTest` 通过
- [ ] `:app:lintDebug` 通过
- [ ] `:app:assembleRelease` 成功
- [ ] `aapt2 dump badging` 显示正确的包名、版本号和版本名称
- [ ] `apksigner verify` 通过，且签名证书与上一正式版本一致
- [ ] 真机完成覆盖安装和核心功能回归
- [ ] Release 附件命名符合 `nishi-X.Y.Z-release.apk`

## 6. 附件命名

正式 Release 附件固定为：

```text
nishi-<versionName>-release.apk
```

例如：

```text
nishi-1.0.2-release.apk
```

不得上传：

- 测试包 `nishi-*-test.apk`
- Demo 包
- 未签名的 Release APK
- 包含本机路径或密钥的构建产物

## 7. 版本变更记录

每个正式 Release 的说明至少包含：

- 新增功能
- 修复内容
- 不兼容变更（如有）
- 主版本包名和版本号

版本规范文件本身不触发版本号变更；只有应用功能或构建产物发生变化时才需要发布新版本。
