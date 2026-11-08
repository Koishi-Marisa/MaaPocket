---
name: "kivo-wiki"
description: "查询国服《蔚蓝档案》角色的全部译名、设定与资料（Kivo Wiki / Blue Archive character lookup）。当用户询问角色译名、国服译名、设定、资料、实装状态，或需要按名称/别名搜索角色时使用。"
---

# Kivo Wiki 角色查询

## 服务信息

- Base URL: `https://kivo.xiaowan.dpdns.org`
- MCP: `https://kivo.xiaowan.dpdns.org/mcp`
- 数据来源: `https://kivo.wiki`
- 范围：搜索索引覆盖国服已实装角色（237 条，含皮肤形态）；未实装角色在本地无结果时会回退到站内搜索。详情支持任意角色 ID，未在国服实装的角色会返回资料并标注状态。

## 在 DSH 中使用

- 项目级安装：把本目录放到 `<项目根>/.agents/skills/kivo-wiki/`。
- 用户级安装：把本目录放到 `~/.agents/skills/kivo-wiki/`。
- DSH 会读取目录下的 `SKILL.md`；当前仓库已经放在 `.agents/skills/kivo-wiki/`。
- 如果 DSH 已配置 MCP，优先调用 MCP 工具；否则使用下面的 REST 接口。

MCP 客户端配置（以客户端实际格式为准）：

```json
{
  "mcpServers": {
    "kivo": {
      "url": "https://kivo.xiaowan.dpdns.org/mcp"
    }
  }
}
```

## 何时使用

当用户询问以下内容时使用：

- 某个《蔚蓝档案》角色的国服译名、日服/韩服/国际服/繁中译名
- 角色的设定、简介、杂项趣闻
- 角色的学校、年级、年龄、身高、生日、兴趣、声优、画师、武器
- 角色的稀有度、定位、攻击/防御类型、适应性、技能、基础数值
- 角色是否在国服实装

不适用：物品、音乐、文章、画廊等非角色内容。

## MCP 工具（优先使用）

如果客户端已连接 MCP，优先调用以下工具。

### search_character

按名称或别称搜索角色。

```json
{
  "query": "未花",
  "limit": 5
}
```

- `query`：国服名、站内名、日文名、英文名、韩文名、繁中名或别称
- `limit`：1–20，默认 5
- 返回结果包含全部语言译名、学校、简介首句、来源链接和 `isCn` 状态

### get_character

按 ID 获取角色完整资料。

```json
{
  "id": 1,
  "sections": ["names", "profile", "intro", "lore", "combat"],
  "max_chars": 8000
}
```

- `sections` 可选：`names`、`profile`、`intro`、`lore`、`combat`、`voices`、`gallery`
- 默认返回 `names,profile,intro,lore,combat`
- `max_chars` 默认 8000，最大 50000
- 未实装角色同样可以获取，返回内容会带「国服状态：未实装」

### list_characters

按学校或分页列出角色。

```json
{
  "school": "崔尼蒂",
  "page": 1,
  "limit": 20
}
```

## REST 回退

如果没有 MCP，使用 HTTP：

| 用途 | 请求 |
|---|---|
| 搜索 JSON | `GET /v1/characters?q=未花&limit=5` |
| 搜索 Markdown | `GET /v1/characters?q=未花&format=markdown` |
| 详情 JSON | `GET /v1/characters/1` |
| 详情 Markdown | `GET /data/character/1` |
| 接口说明 | `GET /llms.txt` |

详情参数：

- `sections=names,profile,intro,lore,combat,voices,gallery`
- `max_chars=8000`
- `images=1` 保留设定中的图片链接

## 返回字段

- `names.cn`：国服译名
- `names.wiki`：站内默认译名
- `names.jp` / `names.kr` / `names.en` / `names.zh_tw`：其他语言译名
- `aliases`：别称
- `profile`：学校、年级、年龄、身高、生日、兴趣、声优、画师、武器、实装日期
- `intro`：国服官方简介；国服未实装时回退为站内简介
- `introSource`：`cn` / `wiki` / `none`
- `lore`：站内设定整理，不是国服官方文案
- `combat`：稀有度、定位、攻击/防御类型、适应性、技能、基础数值
- `release`：国服、日服、国际服的实装状态与日期
- `meta.isCn`：是否已在国服实装

## 快速判断

| 用户问什么 | 取什么 |
|---|---|
| 国服叫什么 / 所有译名 | `names.cn`，需要时加 `names.jp/kr/en/zh_tw` |
| 实装了吗 | `release.cn`、`meta.isCn`、Markdown 顶部「国服状态」 |
| 角色设定 / 剧情 | `intro` + `lore`，注意区分官方与站内整理 |
| 强度 / 技能 / 数值 | `combat` |
| 有哪些角色 | `list_characters` 或搜索 |

## 工作流

1. 先搜索：用用户给出的名字调用 `search_character`，不要直接猜 ID。
2. 如果没搜到，换用别名、日文名、英文名、韩文名或繁中名再试一次。
3. 找到 ID 后调用 `get_character`，默认不要展开语音和图集，除非用户明确要求。
4. 检查 `release.cn` / `meta.isCn` 或 Markdown 顶部的「国服状态」：
   - 已实装：正常回答。
   - 未实装：明确告诉用户「该角色尚未在国服实装」，并说明以下资料来自站内整理。
5. 默认用国服译名回答；用户问「所有译名」时再列出日/韩/英/繁中。
6. 区分 `intro` 和 `lore`：前者优先国服官方简介，后者是站内设定整理，不要混为一谈。
7. 回答末尾附上来源：`https://kivo.wiki/data/character/{id}`。
8. 缺失的数据直接说「暂无资料」，不要编造。

## 未实装角色

- 不要返回 404 或「查不到」；详情接口会返回资料和状态。
- 如果 `introSource` 是 `wiki`，说明简介不是国服官方文案。
- 如果 `combat` 为空或没有有效数值，说明该角色暂无战斗资料，不要补全。
- 典型例子：`千惠留`（ID 596）在国服未实装，应回答「千惠留尚未在国服实装，以下是站内资料」。

## 错误处理

- `404`：角色 ID 不存在，不要继续重试。
- `502`：上游暂时不可用，可以重试一次；仍失败则告知用户稍后再试。
- 搜索无结果：先换别名/其他语言名；仍无结果再说明未找到。

## 回答风格

- 用用户的语言回答。
- 先给结论，再列资料。
- 国服名优先，其他译名放在括号或列表中。
- 不要把整篇 Markdown 原样倒给用户；按问题摘取相关部分。
- 明确区分「国服官方简介」和「站内设定整理」。
- 始终保留来源链接。

## 示例

### 示例 1：国服译名

用户问：「未花在国服叫什么？」

1. `search_character({ "query": "未花" })`
2. 取第一条 `id=1`
3. `get_character({ "id": 1, "sections": ["names", "profile"] })`
4. 回答：国服译名是「圣园 未花」，日服「聖園 ミカ」，国际服「Misono Mika」，繁中「聖園 彌香」。

### 示例 2：未实装角色

用户问：「千惠留实装了吗？」

1. `search_character({ "query": "千惠留" })`
2. 取 `id=596`
3. `get_character({ "id": 596, "sections": ["names", "intro", "profile"] })`
4. 回答：千惠留尚未在国服实装；站内资料显示她是崔尼蒂图书委员会副委员长，简介来自站内整理，不是国服官方文案。

### 示例 3：按学校列角色

用户问：「崔尼蒂有哪些角色？」

1. `list_characters({ "school": "崔尼蒂", "limit": 20 })`
2. 汇总返回的国服译名；如果用户要完整名单，再分页获取。
