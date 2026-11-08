const API_BASE = "https://api.kivo.wiki/api/v1";
const USER_AGENT = "kivo-llm-gateway/0.1 (+https://kivo.wiki; character reader)";

type NextFetchInit = RequestInit & {
  next?: { revalidate?: number };
};

export class UpstreamError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "UpstreamError";
    this.status = status;
  }
}

async function request<T>(path: string, revalidate = 900): Promise<T> {
  const url = `${API_BASE}${path}`;
  let lastError: unknown;

  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const init: NextFetchInit = {
        headers: {
          "user-agent": USER_AGENT,
          accept: "application/json",
        },
        signal: AbortSignal.timeout(8000),
        next: { revalidate },
      };
      const response = await fetch(url, init);
      const body = (await response.json().catch(() => null)) as {
        success?: boolean;
        message?: string;
        data?: T;
      } | null;

      if (!response.ok) {
        throw new UpstreamError(body?.message || `Upstream HTTP ${response.status}`, response.status);
      }
      if (!body?.success || body.data === undefined) {
        throw new UpstreamError(body?.message || "Upstream returned an error", response.status);
      }
      return body.data;
    } catch (error) {
      lastError = error;
      if (error instanceof UpstreamError && error.status >= 400 && error.status < 500) {
        throw error;
      }
      if (attempt < 1) {
        await new Promise((resolve) => setTimeout(resolve, 300));
      }
    }
  }

  throw lastError instanceof Error ? lastError : new Error("Upstream request failed");
}

export interface UpstreamSearchResult {
  id: number;
  type: string;
  title: string;
  body: string;
  tags: string[] | null;
  score: number;
}

export async function getStudent(id: number): Promise<Record<string, any>> {
  return request<Record<string, any>>(`/data/students/${id}`, 900);
}

export async function searchUpstream(keywords: string, pageSize = 10): Promise<UpstreamSearchResult[]> {
  const query = new URLSearchParams({
    keywords,
    page_size: String(pageSize),
    summary_size: "90",
  });
  const data = await request<{ search_results: UpstreamSearchResult[] }>(`/search/?${query}`, 300);
  return data.search_results ?? [];
}
