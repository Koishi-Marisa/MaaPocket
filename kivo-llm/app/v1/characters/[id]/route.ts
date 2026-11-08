import { NextRequest, NextResponse } from "next/server";
import { getCharacterDoc } from "@/lib/characters";
import { renderCharacterMarkdown } from "@/lib/markdown";

export const runtime = "nodejs";

type RouteContext = {
  params: Promise<{ id: string }>;
};

function parseSections(value: string | null): string[] | undefined {
  if (!value) return undefined;
  if (value === "all") return ["names", "profile", "intro", "lore", "combat", "voices", "gallery"];
  return value.split(",").map((item) => item.trim()).filter(Boolean);
}

export async function GET(request: NextRequest, context: RouteContext) {
  const { id: rawId } = await context.params;
  const id = Number(rawId);
  if (!Number.isInteger(id) || id <= 0) {
    return NextResponse.json({ error: "invalid_id" }, { status: 400 });
  }

  const params = request.nextUrl.searchParams;
  const format = params.get("format") ?? "json";

  try {
    const doc = await getCharacterDoc(id);
    if (!doc) {
      return NextResponse.json({ error: "character_not_found", id }, { status: 404 });
    }

    if (format === "markdown" || format === "md") {
      const markdown = renderCharacterMarkdown(doc, {
        sections: parseSections(params.get("sections")),
        maxChars: Number(params.get("max_chars")) || undefined,
        keepImages: params.get("images") === "1",
      });
      return new NextResponse(markdown, {
        headers: {
          "content-type": "text/markdown; charset=utf-8",
          "cache-control": "public, s-maxage=900, stale-while-revalidate=86400",
          "access-control-allow-origin": "*",
        },
      });
    }

    return NextResponse.json(doc, {
      headers: {
        "cache-control": "public, s-maxage=900, stale-while-revalidate=86400",
        "access-control-allow-origin": "*",
      },
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    return NextResponse.json({ error: "upstream_error", message }, { status: 502 });
  }
}
