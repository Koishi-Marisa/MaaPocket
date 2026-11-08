import type {
  CharacterDoc,
  CharacterSearchResult,
  RenderOptions,
} from "@/lib/types";

const DEFAULT_SECTIONS = ["names", "profile", "intro", "lore", "combat"];

export function absoluteUrl(raw: string): string {
  const url = raw.trim();
  if (url.startsWith("//")) return `https:${url}`;
  if (url.startsWith("http://") || url.startsWith("https://")) return url;
  if (url.startsWith("files/")) return `https://static.kivo.wiki/${url}`;
  if (url.startsWith("/")) return `https://kivo.wiki${url}`;
  return url;
}

function convertCallouts(input: string): string {
  const lines = input.split("\n");
  const output: string[] = [];
  let inCallout = false;

  for (const line of lines) {
    const open = line.match(/^!!![ \t]*(.*)$/);
    if (open) {
      if (!inCallout) {
        inCallout = true;
        const rest = open[1]
          .replace(/^(note|tip|warning|danger|info|question|attention|success|failure)\b[ \t]*/i, "")
          .trim();
        if (rest) output.push(`**${rest.replace(/\*\*/g, "")}**`, "");
        else output.push("");
      } else {
        inCallout = false;
        output.push("");
      }
      continue;
    }

    if (inCallout) output.push(line.trim() ? `> ${line}` : ">");
    else output.push(line);
  }

  return output.join("\n");
}

export function cleanMarkdown(input: string, keepImages = false): string {
  if (!input) return "";

  let text = input
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/?(?:font|span|div|details|summary|p|center|b|i|u|table|thead|tbody|tr|td|th)[^>]*>/gi, "")
    .replace(/&emsp;?/gi, "  ")
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"');

  text = text.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, (_match, alt: string, target: string) => {
    if (!keepImages) return "";
    const url = absoluteUrl(target.trim().split(/\s+/)[0] ?? "");
    return `![${alt}](${url})`;
  });

  text = convertCallouts(text)
    .replace(/\[\[toc\]\]/gi, "")
    .replace(/^:{3,}.*$/gm, "");

  return text
    .split("\n")
    .map((line) => line.replace(/[ \t]+$/g, ""))
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

export function joinName(family: string, given: string): string {
  return `${family ?? ""} ${given ?? ""}`.trim();
}

export function splitAliases(value: string | null | undefined): string[] {
  if (!value) return [];
  return value
    .split(/[,，、;；]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function bullet(label: string, value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === "") return "";
  return `- ${label}：${value}`;
}

function namesSection(doc: CharacterDoc): string {
  const rows: Array<[string, string]> = [
    ["国服（简中）", doc.names.cn || (doc.meta.cnMissing ? "暂无" : "")],
    ["站内默认", doc.names.wiki],
    ["日服", doc.names.jp],
    ["韩服", doc.names.kr],
    ["国际服（英）", doc.names.en],
    ["繁中", doc.names.zh_tw],
  ].filter(([, value]) => Boolean(value)) as Array<[string, string]>;

  const lines = [
    "## 译名",
    "",
    "| 版本 | 译名 |",
    "|---|---|",
    ...rows.map(([label, value]) => `| ${label} | ${value} |`),
  ];

  if (doc.aliases.length > 0) {
    lines.push("", `- 别称：${doc.aliases.join("、")}`);
  }

  const skinNames = [doc.skin.cn, doc.skin.jp, doc.skin.zh_tw].filter(Boolean);
  if (skinNames.length > 0) {
    lines.push(`- 皮肤：${skinNames.join(" / ")}`);
  } else {
    const others = doc.skins
      .filter((skin) => skin.id !== doc.id && skin.cn)
      .map((skin) => skin.cn);
    if (others.length > 0) {
      lines.push(`- 其他形态：${[...new Set(others)].join("、")}`);
    }
  }

  return lines.join("\n");
}

function profileSection(doc: CharacterDoc): string {
  const p = doc.profile;
  const bullets = [
    bullet("学校", p.school),
    bullet("年级", p.grade),
    bullet("年龄", p.age),
    bullet("身高", p.height ? `${p.height} cm` : ""),
    bullet("生日", p.birthday),
    bullet("兴趣", p.hobby),
    bullet("声优", [p.voiceJp, p.voiceCn && `国服：${p.voiceCn}`].filter(Boolean).join(" / ")),
    bullet("画师", p.illustrator),
    bullet("设计", p.designer),
    bullet("武器", p.weaponType),
    bullet("日服实装", p.releaseJp),
    bullet("国服实装", p.releaseCn),
    bullet("国际服实装", p.releaseGlobal),
    bullet("MomoTalk 签名", p.momoTalk),
  ].filter(Boolean);

  return ["## 基本资料", "", ...bullets].join("\n");
}

function introSection(doc: CharacterDoc): string {
  if (!doc.intro) return "";
  const heading = doc.introSource === "cn" ? "## 国服简介" : "## 站内简介（非国服）";
  return [heading, "", cleanMarkdown(doc.intro)].join("\n");
}

function loreSection(doc: CharacterDoc, keepImages: boolean): string {
  if (!doc.lore) return "";
  const lore = cleanMarkdown(doc.lore, keepImages)
    .replace(/^#[ \t]+.*$/gm, "")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  if (!lore) return "";
  return [
    "## 站内设定整理",
    "",
    "> 以下内容来自基沃托斯古书馆站内整理，不是国服官方文案。",
    "",
    lore,
  ].join("\n");
}

function combatSection(doc: CharacterDoc): string {
  const c = doc.combat;
  const hasCombat =
    (c.rarity !== null && c.rarity > 0) ||
    Boolean(c.role || c.attackType || c.defenseType || c.weaponName) ||
    c.exSkills.length > 0 ||
    c.otherSkills.length > 0 ||
    Object.values(c.stats).some((value) => value > 0);

  if (!hasCombat) return "";

  const bullets = [
    bullet("稀有度", c.rarity ? `${c.rarity}★` : ""),
    bullet("定位", c.role),
    bullet("攻击类型", c.attackType),
    bullet("防御类型", c.defenseType),
    bullet("站位", [c.teamPosition, c.battlefieldPosition].filter(Boolean).join(" / ")),
    bullet(
      "适应性",
      [c.adaptability.outdoor && `室外 ${c.adaptability.outdoor}`, c.adaptability.indoor && `室内 ${c.adaptability.indoor}`, c.adaptability.street && `街道 ${c.adaptability.street}`]
        .filter(Boolean)
        .join(" / "),
    ),
    bullet("武器", c.weaponName),
  ].filter(Boolean);
  const lines = ["## 战斗资料", "", ...bullets];

  if (c.weaponDescription) {
    lines.push("", `> ${c.weaponDescription}`);
  }

  if (c.exSkills.length > 0) {
    lines.push("", "### EX 技能", "");
    for (const skill of c.exSkills) {
      lines.push(`- **${skill.title}**${skill.cost ? `（Cost ${skill.cost}）` : ""}`);
      if (skill.description) lines.push(`  - ${skill.description}`);
    }
  }

  if (c.otherSkills.length > 0) {
    lines.push("", "### 其他技能", "");
    for (const skill of c.otherSkills) {
      lines.push(`- **${skill.title}**`);
      if (skill.description) lines.push(`  - ${skill.description}`);
    }
  }

  const entries = Object.entries(c.stats).filter(([, value]) => typeof value === "number");
  if (entries.length > 0) {
    lines.push(
      "",
      "### 基础数值",
      "",
      "| 项目 | 数值 |",
      "|---|---|",
      ...entries.map(([key, value]) => `| ${key} | ${value} |`),
    );
  }

  return lines.join("\n");
}

function voicesSection(doc: CharacterDoc): string {
  if (doc.voices.length === 0) return "";
  const lines = ["## 语音", ""];
  for (const voice of doc.voices.slice(0, 40)) {
    const text = voice.text || "（无文本）";
    lines.push(`- [${voice.category}] ${voice.description || ""}：${text}`);
    if (voice.file) lines.push(`  - ${voice.file}`);
  }
  if (doc.voices.length > 40) {
    lines.push(`- 其余 ${doc.voices.length - 40} 条语音已省略`);
  }
  return lines.join("\n");
}

function gallerySection(doc: CharacterDoc): string {
  if (doc.gallery.length === 0) return "";
  const lines = ["## 图集", ""];
  for (const group of doc.gallery) {
    lines.push(`- **${group.title}**（${group.images.length} 张）`);
    lines.push(...group.images.slice(0, 3).map((image) => `  - ${image}`));
    if (group.images.length > 3) lines.push(`  - 其余 ${group.images.length - 3} 张已省略`);
  }
  return lines.join("\n");
}

export function renderCharacterMarkdown(doc: CharacterDoc, options: RenderOptions = {}): string {
  const sections = new Set(options.sections?.length ? options.sections : DEFAULT_SECTIONS);
  const maxChars = options.maxChars ?? 8000;
  const footer = [`> 来源：${doc.meta.source}`, doc.meta.updatedAt && `> 更新：${doc.meta.updatedAt.slice(0, 10)}`]
    .filter(Boolean)
    .join("\n");

  const status = doc.release.cn
    ? `> 国服状态：已实装${doc.release.cnDate ? `（${doc.release.cnDate}）` : ""}`
    : "> 国服状态：未实装（以下为站内资料，不代表国服内容）";
  const blocks: string[] = [`# ${doc.displayName}`, footer, status];

  if (sections.has("names")) blocks.push(namesSection(doc));
  if (sections.has("profile")) blocks.push(profileSection(doc));
  if (sections.has("intro")) blocks.push(introSection(doc));
  if (sections.has("lore")) blocks.push(loreSection(doc, Boolean(options.keepImages)));
  if (sections.has("combat")) blocks.push(combatSection(doc));
  if (sections.has("voices")) blocks.push(voicesSection(doc));
  if (sections.has("gallery")) blocks.push(gallerySection(doc));

  let output = blocks
    .filter((block) => block.trim().length > 0)
    .join("\n\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();

  if (output.length > maxChars) {
    const budget = Math.max(0, maxChars - 120);
    const slice = output.slice(0, budget);
    const lastBreak = Math.max(
      slice.lastIndexOf("\n\n"),
      slice.lastIndexOf("\n"),
      slice.lastIndexOf("。"),
      slice.lastIndexOf("！"),
      slice.lastIndexOf("？"),
      slice.lastIndexOf("；"),
      slice.lastIndexOf(". "),
    );
    const cut = lastBreak > budget * 0.5 ? slice.slice(0, lastBreak) : slice;
    output = `${cut.trim()}\n\n> 内容过长，已截断。完整内容请访问 ${doc.meta.source}`;
  }

  return `${output}\n`;
}

export function renderSearchMarkdown(results: CharacterSearchResult[], query: string): string {
  if (results.length === 0) {
    return `# 搜索结果：${query}\n\n没有找到国服角色。\n`;
  }

  const lines = [`# 搜索结果：${query}`, "", `共 ${results.length} 条。`, ""];
  results.forEach((result, index) => {
    lines.push(`${index + 1}. **${result.displayName}** — \`character:${result.id}\``);
    const aliases = [
      result.names.cn && `国服：${result.names.cn}`,
      result.names.jp && `日服：${result.names.jp}`,
      result.names.en && `英文：${result.names.en}`,
      result.names.zh_tw && `繁中：${result.names.zh_tw}`,
      result.aliases.length > 0 && `别称：${result.aliases.join("、")}`,
    ].filter(Boolean);
    if (aliases.length > 0) lines.push(`   ${aliases.join(" | ")}`);
    if (!result.isCn) lines.push("   国服状态：未实装");
    if (result.school) lines.push(`   学校：${result.school}`);
    if (result.intro) lines.push(`   简介：${result.intro}`);
    lines.push(`   链接：${result.url}`);
  });
  return `${lines.join("\n")}\n`;
}
