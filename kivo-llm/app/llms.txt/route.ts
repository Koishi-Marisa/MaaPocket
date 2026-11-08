import { NextRequest } from "next/server";

export const runtime = "nodejs";

export async function GET(request: NextRequest) {
  const origin = request.nextUrl.origin;
  const body = `# 基沃托斯古书馆 LLM Gateway

> 只读的国服《蔚蓝档案》角色资料接口。返回国服译名、全部语言译名、角色设定与战斗资料。

## 范围

- 搜索索引覆盖国服已实装角色（含皮肤形态），共 237 条。
- 详情接口支持站内任意角色 ID；未在国服实装的会返回角色资料，并在开头标注「国服状态：未实装」。
- 数据来源：https://kivo.wiki 与 https://api.kivo.wiki
- 内容版权归基沃托斯古书馆及原作者所有；本接口仅做检索与格式转换。

## REST API

- 搜索角色：GET ${origin}/v1/characters?q=未花&limit=5
- 搜索并直接返回 Markdown：GET ${origin}/v1/characters?q=未花&format=markdown
- 角色详情 JSON：GET ${origin}/v1/characters/1
- 角色详情 Markdown：GET ${origin}/data/character/1
- 按学校列出：GET ${origin}/v1/characters?school=崔尼蒂&limit=20

详情参数：

- sections=names,profile,intro,lore,related,combat,voices,gallery
- max_chars=8000
- images=1（保留设定中的图片链接）

## MCP

- Endpoint: ${origin}/mcp
- Tools:
  - search_character(query, limit)
  - get_character(id, sections, max_chars)
  - list_characters(school, page, limit)

## 返回字段

- names.cn：国服译名
- names.wiki：站内默认译名
- names.jp / kr / en / zh_tw：其他语言译名
- aliases：别称
- profile：学校、年级、年龄、身高、生日、兴趣、声优、画师、武器、实装日期
- intro：国服官方简介；国服未实装时回退为站内简介
- introSource：cn / wiki / none
- lore：站内设定整理（非国服官方文案）
- related：相关角色
- combat：稀有度、定位、攻击/防御类型、适应性、技能、基础数值
- release：国服、日服、国际服的实装状态与日期
- meta.isCn：是否已在国服实装

## 示例

GET ${origin}/v1/characters?q=未花

\`\`\`json
{
  "query": "未花",
  "count": 1,
  "results": [
    {
      "id": 1,
      "displayName": "圣园 未花",
      "names": {
        "cn": "圣园 未花",
        "jp": "聖園 ミカ",
        "kr": "미소노 미카",
        "en": "Misono Mika",
        "zh_tw": "聖園 彌香"
      },
      "url": "https://kivo.wiki/data/character/1"
    }
  ]
}
\`\`\`

## 使用约定

- 建议缓存 15 分钟，避免高频回源。
- 请保留来源链接与版权说明。
- 如站方要求下线，请立即停止使用。
`;
  return new Response(body, {
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "public, max-age=3600",
      "access-control-allow-origin": "*",
    },
  });
}
