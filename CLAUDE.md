# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

个人资产快照管理系统 —— 记录每日资产状态（现金、投资、负债、净资产），而非交易流水。是一个前后端分离的全栈项目。

## 目录结构

```
water/
├── water-server/              # Spring Boot 后端
│   ├── src/main/java/com/water/server/
│   │   └── snapshot/           # 快照相关代码（controllers、services、DTOs）
│   ├── src/main/resources/
│   │   ├── application.yml     # 配置（数据库路径、端口）
│   │   └── schema.sql         # SQLite 建表语句 + 种子数据
│   └── src/test/java/         # 单元测试
├── water-web/                 # Vue 3 前端
│   ├── src/
│   │   ├── App.vue            # 主页面，含哈希路由
│   │   ├── main.js
│   │   └── styles.css
│   └── package.json
├── water.db                   # 本地 SQLite 数据库
├── water.csv                  # 原始数据
└── water.sql                 # 建表脚本参考
```

## 构建与运行

### 后端 (water-server)

```bash
cd water-server
mvn spring-boot:run          # 启动服务，端口 8080
mvn test                    # 运行所有测试
mvn test -Dtest=类名         # 运行单个测试类
mvn test -Dtest=类名#方法名   # 运行单个测试方法
```

### 前端 (water-web)

```bash
cd water-web
npm install                  # 安装依赖
npm run dev                  # 启动开发服务器，端口 5173
npm run build                # 生产构建
```

**注意**：Vite 开发服务器将 `/api/*` 代理到 `http://localhost:8080`，本地开发需先启动后端。

### 数据库配置

SQLite 数据库路径在 `water-server/src/main/resources/application.yml` 中配置：
- 使用环境变量 `WATER_DB_PATH`，默认为 `../water.db`
- 通过 `sql.init.mode: always` 自动初始化 schema

## 后端架构

### 服务模式（类 CQRS）

后端采用命令/查询分离模式：
- `*QueryService` — 读操作（如 `AssetSnapshotQueryService`）
- `*CommandService` — 写操作（如 `AssetSnapshotCommandService`）

### 核心表结构

- `currency_config` — 支持的货币（CNY、USD、HKD）
- `asset_account` — 账户定义，含类型（EWALLET、BANK_CARD、INVESTMENT、CREDIT_CARD 等）
- `asset_snapshot` — 每日快照，含汇总数据
- `asset_snapshot_detail` — 快照中每个账户的金额

### API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/snapshots` | 获取所有快照 |
| GET | `/api/snapshots/all` | 获取所有快照（别名） |
| GET | `/api/snapshots/accounts` | 获取启用的账户定义列表 |
| POST | `/api/snapshots` | 新建快照 |
| PUT | `/api/snapshots/{id}` | 更新快照 |
| DELETE | `/api/snapshots/{id}` | 删除快照 |
| GET | `/api/accounts` | 获取所有账户 |
| POST | `/api/accounts` | 新建账户 |
| PUT | `/api/accounts/{id}` | 更新账户 |
| DELETE | `/api/accounts/{id}` | 删除账户 |
| GET | `/api/import/preview` | 预览 CSV 导入 |

## 前端架构

单页应用，使用哈希路由（无 Vue Router）：
- `#/` 或无哈希 → 首页（快照列表）
- `#/accounts` → 账户管理页

所有 API 调用使用原生 `fetch`，无 axios 等库依赖。

## 数据模型说明

- 快照日期格式为 `YYYY-MM-DD`
- 原始 CSV 中的编码字段（`cash`、`asset`、`loan`）未使用，改为通过 `asset_snapshot_detail` 存储每个账户的金额
- 账户代码（如 `ALIPAY`、`WECHAT`、`FUND_ACCOUNT`）为稳定标识符
- 多币种支持：每个账户有 `currency_code`（CNY/USD/HKD）
