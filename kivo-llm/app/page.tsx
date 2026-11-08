const endpoints = [
  {
    name: "搜索角色",
    path: "/v1/characters?q=未花&limit=5",
    note: "返回 JSON；加 format=markdown 直接返回 Markdown。",
  },
  {
    name: "角色详情",
    path: "/v1/characters/1?format=markdown",
    note: "全部译名、基本资料、国服简介、站内设定、战斗资料。",
  },
  {
    name: "路径镜像",
    path: "/data/character/1",
    note: "直接返回 Markdown，适合网页 fetch 类工具。",
  },
  {
    name: "llms.txt",
    path: "/llms.txt",
    note: "给 LLM 的接口说明与使用约定。",
  },
  {
    name: "MCP",
    path: "/mcp",
    note: "Streamable HTTP MCP 端点，提供 search_character、get_character、list_characters。",
  },
];

export default function Home() {
  return (
    <main className="page">
      <header className="hero">
        <p className="eyebrow">KIVO LLM GATEWAY</p>
        <h1>国服角色资料，面向 LLM 的只读接口</h1>
        <p className="lede">
          基于基沃托斯古书馆公开数据，返回国服译名、全部语言译名、角色设定与战斗资料。
          默认 Markdown，搜索支持中文、日文、英文、韩文、繁中与别称。
        </p>
        <div className="actions">
          <a className="primary" href="/v1/characters?q=%E6%9C%AA%E8%8A%B1&format=markdown">
            试一次搜索
          </a>
          <a href="/llms.txt">查看 llms.txt</a>
          <a href="/data/character/1">查看 Markdown 详情</a>
        </div>
      </header>

      <section className="grid">
        {endpoints.map((endpoint) => (
          <article className="card" key={endpoint.path}>
            <h2>{endpoint.name}</h2>
            <code>{endpoint.path}</code>
            <p>{endpoint.note}</p>
          </article>
        ))}
      </section>

      <section className="panel">
        <h2>MCP 配置</h2>
        <p>把地址换成你的 Vercel 域名，然后在支持远程 MCP 的客户端里添加：</p>
        <pre>{`{
  "mcpServers": {
    "kivo": {
      "url": "https://your-project.vercel.app/mcp"
    }
  }
}`}</pre>
      </section>

      <section className="panel">
        <h2>范围与说明</h2>
        <ul>
          <li>仅国服已实装角色，含皮肤形态，共 237 条。</li>
          <li>`names.cn` 是国服译名，`names.wiki` 是站内默认译名，其余为日/韩/英/繁中。</li>
          <li>`intro` 是国服官方简介；`lore` 是站内设定整理，不是国服官方文案。</li>
          <li>内容版权归基沃托斯古书馆及原作者所有，请保留来源链接。</li>
        </ul>
      </section>
    </main>
  );
}
