# amc CLI (TypeScript)

面向 `memoh -> cli -> api -> server` 的 deterministic 执行器。
当前已接入 AgentFS，会将每次命令的输入/输出/错误写入会话存储，便于审计和复盘。

## 能力范围

- `preflight`: 健康检查 + 认证连通性
- `act:*`: 执行动作（tap/swipe/back/home/text/launch/stop）
- `observe:*`: 状态观测（screen/screenshot/top）
- `verify:*`: 断言校验（text/top-activity/node）
- `recover:*`: 恢复动作（back/home/relaunch）

## 安装与构建

```bash
cd cli
npm install
npm run build
npm link
```

然后可直接使用：

```bash
amc --help
```

## 全局参数

- `--base-url` 默认 `http://127.0.0.1:8081`
- `--token` 默认读取 `AMCTL_TOKEN`
- `--timeout-ms` 默认 `10000`
- `--session` AgentFS 会话 ID，默认 `AMCTL_SESSION` 或 `default`

## 示例

```bash
# 1) 预检
amc preflight --base-url http://127.0.0.1:8081

# 2) 点击
amc act:tap --base-url http://127.0.0.1:8081 --token "$AMCTL_TOKEN" --x 540 --y 1200

# 3) 校验文案存在
amc verify:text-contains --base-url http://127.0.0.1:8081 --token "$AMCTL_TOKEN" --text "设置"

# 4) 恢复到主页
amc recover:home --base-url http://127.0.0.1:8081 --token "$AMCTL_TOKEN"

# 5) 指定 agentfs 会话
amc act:tap --base-url http://127.0.0.1:8081 --token "$AMCTL_TOKEN" --session task-001 --x 540 --y 1200
```

## 输出约定

所有命令输出单行 JSON，便于上层 agent 直接解析：

- 成功: `{"ok":true,"code":"OK",...}`
- 失败: `{"ok":false,"code":"...",...}`

错误码：

- `INVALID_PARAMS`
- `NETWORK_ERROR`
- `AUTH_ERROR`
- `SERVER_ERROR`
- `ASSERTION_FAILED`
- `INTERNAL_ERROR`

## AgentFS 记录

每次命令会写入：

- `/amctl/<session>/commands/*_input.json`
- `/amctl/<session>/commands/*_output.json`
- `/amctl/<session>/commands/*_error.json`

并更新最新状态键：

- `amctl/<session>/latest/<command>/input|output|error`

如果运行环境无法加载 `agentfs-sdk`，CLI 会自动回退到本地目录：

- `.amctl-sessions/<session>/commands/*.json`
