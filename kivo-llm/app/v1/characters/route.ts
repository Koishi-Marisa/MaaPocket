import { NextRequest, NextResponse } from "next/server";
import { listCharacters, searchCharacters } from "@/lib/characters";
import { renderSearchMarkdown } from "@/lib/markdown";

export const runtime = "nodejs";

function markdownResponse(body: string) {
  return new NextResponse(body, {
    headers: {
      "content-type": "text/markdown; charset=utf-8",
      "cache-control": "public, s-maxage=300, stale-while-revalidate=3600",
      "access-control-allow-origin": "*",
    },
  });
}

export async function GET(request: NextRequest) {
  const params = request.nextUrl.searchParams;
  const query = (params.get("q") ?? "").trim();
  const school = (params.get("school") ?? "").trim();
  const format = params.get("format") ?? "json";
  const limitParam = params.get("limit");
  const limit = limitParam ? Number(limitParam) : undefined;
  const page = Number(params.get("page") ?? 1);

  try {
    if (query) {
      const results = await searchCharacters(query, limit && Number.isFinite(limit) ? limit : 5);
      if (format === "markdown" || format === "md") {
        return markdownResponse(renderSearchMarkdown(results, query));
      }
      return NextResponse.json(
        { query, count: results.length, results },
        {
          headers: {
            "cache-control": "public, s-maxage=300, stale-while-revalidate=3600",
            "access-control-allow-origin": "*",
          },
        },
      );
    }

    const data = listCharacters({
      school,
      page: Number.isFinite(page) ? page : 1,
      limit: limit && Number.isFinite(limit) ? limit : 20,
    });

    if (format === "markdown" || format === "md") {
      return markdownResponse(renderSearchMarkdown(data.results, school ? `学校：${school}` : "国服角色列表"));
    }

    return NextResponse.json(data, {
      headers: {
        "cache-control": "public, s-maxage=600, stale-while-revalidate=3600",
        "access-control-allow-origin": "*",
      },
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    return NextResponse.json({ error: "upstream_error", message }, { status: 502 });
  }
}
