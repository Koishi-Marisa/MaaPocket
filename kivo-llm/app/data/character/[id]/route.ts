import { NextRequest } from "next/server";
import { getCharacterDoc } from "@/lib/characters";
import { renderCharacterMarkdown } from "@/lib/markdown";

export const runtime = "nodejs";

type RouteContext = {
  params: Promise<{ id: string }>;
};

export async function GET(request: NextRequest, context: RouteContext) {
  const { id: rawId } = await context.params;
  const id = Number(rawId);
  if (!Number.isInteger(id) || id <= 0) {
    return new Response("无效的角色 ID\n", { status: 400 });
  }

  const params = request.nextUrl.searchParams;
  try {
    const doc = await getCharacterDoc(id);
    if (!doc) {
      return new Response("未找到该角色\n", { status: 404 });
    }

    const markdown = renderCharacterMarkdown(doc, {
      sections: params.get("sections")
        ? params.get("sections")!.split(",").map((item) => item.trim()).filter(Boolean)
        : undefined,
      maxChars: Number(params.get("max_chars")) || undefined,
      keepImages: params.get("images") === "1",
    });

    return new Response(markdown, {
      headers: {
        "content-type": "text/markdown; charset=utf-8",
        "cache-control": "public, s-maxage=900, stale-while-revalidate=86400",
        "access-control-allow-origin": "*",
        "x-content-source": doc.meta.source,
      },
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    return new Response(`上游请求失败：${message}\n`, { status: 502 });
  }
}
