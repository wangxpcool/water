# water

一个用于管理个人资产快照的前后端分离项目。当前仓库包含：

- `water-server`：Spring Boot 后端，负责读取 SQLite 数据并提供资产快照接口
- `water-web`：Vue 3 + Vite 前端，用于展示资产总览和快照明细
- `water.csv` / `water.sql` / `water.db`：原始数据、建表脚本和本地 SQLite 数据库

## 项目目标

这个项目的核心不是记账流水，而是记录某一天的资产状态快照。每一条记录表示某个时间点的：

- 现金
- 投资
- 负债
- 公积金/公共资金
- 净资产

前端按时间倒序展示这些快照，并支持展开查看账户明细。

## 目录结构

```text
water/
├─ water-server/              # Spring Boot 后端
├─ water-web/                 # Vue 3 前端
├─ water.csv                  # 原始资产快照数据
├─ water.sql                  # SQLite 初始化脚本
├─ water.db                   # 本地 SQLite 数据库
└─ import-water-new-schema.ps1
```

## 技术栈

### 后端

- Java 17
- Spring Boot 3.4.3
- Spring Web
- Spring JDBC
- SQLite
- Apache Commons CSV

### 前端

- Vue 3
- Vite

## 快速启动

### 1. 启动后端

先确认 Maven 使用 Java 17。Windows PowerShell 可以临时指定：

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
```

在仓库根目录执行：

```bash
cd water-server
mvn spring-boot:run
```

默认启动地址：

```text
http://localhost:8080
```

后端会自动在当前目录和上一级目录查找 `water.db`，通常从仓库根目录或 `water-server` 目录启动都能找到。也可以通过 `WATER_DB_PATH` 覆盖。Windows PowerShell 示例：

```powershell
$env:WATER_DB_PATH="..\water.db"
```

### 2. 启动前端

另开一个终端执行：

```bash
cd water-web
npm install
npm run dev
```

默认前端地址：

```text
http://localhost:5173
```

Vite 已配置将 `/api/*` 代理到 `http://localhost:8080`，所以本地开发时需要先启动后端。

## 当前接口

### 资产快照

```text
GET /api/snapshots
GET /api/snapshots/all
```

返回全部资产快照及其账户明细，供前端总览页面使用。

### CSV 导入预览

```text
GET /api/import/preview
```

可选参数：

```text
path=../water.csv
```

用于预览 CSV 解析结果，便于调试导入逻辑。

示例：

```bash
curl "http://localhost:8080/api/import/preview"
```

## 数据说明

`water.csv` 中保存的是资产快照，不是交易流水。仓库里已经包含一份整理后的数据模型设计，见：

- `water-server/docs/data-model.md`

当前数据中重点关注的字段包括：

- `time`：快照日期
- `income`：收入基线
- `red`：固定支出
- `total1`：现金总额
- `total2`：投资总额
- `total3`：负债总额
- `actual`：净资产
- `publicFunds`：公共储备/家庭储备
- `balance`：余额

## 当前能力

- 从 SQLite 查询资产快照数据
- 返回每个快照下的账户明细
- 前端展示最新净资产、总资产、总负债
- 支持查看历史快照列表和展开详情
- 支持 CSV 导入预览接口

## 后续可扩展方向

- 完善 CSV 到结构化表的正式导入流程
- 增加新增/编辑/删除快照能力
- 增加图表趋势分析
- 支持多币种和更细的账户分类
- 增加鉴权和数据备份能力

## 参考

- 后端说明：`water-server/README.md`
- 前端说明：`water-web/README.md`
