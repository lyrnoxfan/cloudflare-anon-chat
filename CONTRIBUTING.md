# 贡献指南 · Contributing

感谢参与轻聊！以下约定有助于改动被顺利合并。

## 开发环境

- Node ≥ 22，pnpm 10
- 可选：Android SDK 35 / NDK 27.2 / CMake 3.22（仅原生客户端）

```bash
pnpm install --frozen-lockfile
cp .dev.vars.example .dev.vars   # DEV_LOCAL=true，APP_SECRET 自备
pnpm db:local
pnpm dev
```

## 提交前自检

```bash
pnpm check   # 类型检查 + 本地测试 + JS 语法 + 打包预演
```

Android 改动：

```bash
cd android && ./gradlew assembleDebug
```

## 分支与提交

- 从 `main` 拉出特性分支（如 `feat/xxx`、`fix/xxx`）。
- 提交信息使用 Conventional Commits 风格：`feat:` / `fix:` / `chore:` / `docs:` / `refactor:`。
- 一个 PR 聚焦一件事；附带必要的测试或复现步骤。

## 代码风格

- TypeScript / JavaScript 保持现有紧凑、参数化 SQL、转义渲染的写法。
- 前端渲染一律使用 `textContent` / `createElement`，不要拼接 `innerHTML`。
- 新增或修改安全相关逻辑（鉴权、限流、票据、上传确认）请在 PR 中说明威胁模型。
- 不要提交 `.dev.vars`、`.wrangler/`、`.build/`、`node_modules/`、密钥或私钥。

## 安全问题

请勿公开 Issue，先阅读 [SECURITY.md](SECURITY.md) 通过私下渠道联系维护者。
