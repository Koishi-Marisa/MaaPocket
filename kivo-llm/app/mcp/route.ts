import { createMcpHandler } from "mcp-handler";
import { z } from "zod";
import { getCharacterDoc, listCharacters, searchCharacters } from "@/lib/characters";
import { renderCharacterMarkdown, renderSearchMarkdown } from "@/lib/markdown";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const SECTIONS = ["names", "profile", "intro", "lore", "combat", "voices", "gallery"] as const;

const handler = createMcpHandler(
  (server) => {
    server.registerTool(
      "search_character",
      {
        title: "搜索国服角色",
        description:
          "按国服译名、站内译名、日文名、英文名、韩文名、繁中名或别称搜索《蔚蓝档案》国服角色。返回全部语言译名。",
        inputSchema: z.object({
          query: z.string().describe("角色名或别称，例如：未花、mika、Misono Mika"),
          limit: z.number().int().min(1).max(20).optional().default(5),
        }),
      },
      async ({ query, limit }) => {
        try {
          const results = await searchCharacters(query, limit ?? 5);
          return {
            content: [{ type: "text", text: renderSearchMarkdown(results, query) }],
          };
        } catch (error) {
          return {
            content: [{ type: "text", text: `搜索失败：${error instanceof Error ? error.message : "未知错误"}` }],
            isError: true,
          };
        }
      },
    );

    server.registerTool(
      "get_character",
      {
        title: "获取国服角色资料",
        description:
          "获取一个国服已实装角色的全部译名、基本资料、国服简介、站内设定整理和战斗资料。",
        inputSchema: z.object({
          id: z.number().int().positive().describe("角色 ID，例如圣园未花是 1"),
          sections: z.array(z.enum(SECTIONS)).optional().describe("默认返回 names,profile,intro,lore,combat"),
          max_chars: z.number().int().min(500).max(50000).optional().describe("默认 8000"),
        }),
      },
      async ({ id, sections, max_chars }) => {
        try {
          const doc = await getCharacterDoc(id);
          if (!doc) {
            return {
              content: [{ type: "text", text: `未找到 ID 为 ${id} 的国服已实装角色。` }],
              isError: true,
            };
          }
          return {
            content: [
              {
                type: "text",
                text: renderCharacterMarkdown(doc, {
                  sections,
                  maxChars: max_chars ?? 8000,
                }),
              },
            ],
          };
        } catch (error) {
          return {
            content: [{ type: "text", text: `获取失败：${error instanceof Error ? error.message : "未知错误"}` }],
            isError: true,
          };
        }
      },
    );

    server.registerTool(
      "list_characters",
      {
        title: "列出国服角色",
        description: "按学校或分页列出国服已实装角色。",
        inputSchema: z.object({
          school: z.string().optional().describe("学校名或学校 ID，例如：崔尼蒂、阿拜多斯"),
          page: z.number().int().min(1).optional().default(1),
          limit: z.number().int().min(1).max(50).optional().default(20),
        }),
      },
      async ({ school, page, limit }) => {
        try {
          const data = listCharacters({ school, page, limit });
          const text = renderSearchMarkdown(
            data.results,
            school ? `学校：${school}（第 ${data.page} 页）` : `国服角色（第 ${data.page} 页）`,
          );
          return {
            content: [
              {
                type: "text",
                text: `${text}\n共 ${data.total} 条，当前第 ${data.page} 页，每页 ${data.limit} 条。\n`,
              },
            ],
          };
        } catch (error) {
          return {
            content: [{ type: "text", text: `列出失败：${error instanceof Error ? error.message : "未知错误"}` }],
            isError: true,
          };
        }
      },
    );
  },
  {
    serverInfo: { name: "kivo-llm", version: "0.1.0" },
    verboseLogs: false,
  },
);

export { handler as GET, handler as POST };
