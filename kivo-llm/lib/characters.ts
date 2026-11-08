import charactersJson from "@/data/characters.json";
import schoolsJson from "@/data/schools.json";
import type {
  CharacterCombat,
  CharacterDoc,
  CharacterGalleryGroup,
  CharacterIndexEntry,
  CharacterNames,
  CharacterProfile,
  CharacterSearchResult,
  CharacterSkill,
  CharacterVoice,
  SkinEntry,
} from "@/lib/types";
import { getStudent, searchUpstream, UpstreamError } from "@/lib/upstream";
import { absoluteUrl, joinName, splitAliases } from "@/lib/markdown";

const CHARACTERS = charactersJson as unknown as CharacterIndexEntry[];
const SCHOOLS = schoolsJson as Array<{ id: number; name: string; nameCn: string }>;
const BY_ID = new Map(CHARACTERS.map((entry) => [entry.id, entry]));
const SCHOOL_BY_ID = new Map(SCHOOLS.map((school) => [school.id, school]));

const ROLE_LABELS: Record<string, string> = {
  Dealer: "输出",
  Healer: "治疗",
  Support: "辅助",
  Tank: "坦克",
};

const ATTACK_LABELS: Record<string, string> = {
  Explosive: "爆发",
  Piercing: "贯穿",
  Mystic: "神秘",
  Vibration: "振动",
  Sonic: "振动",
};

const DEFENSE_LABELS: Record<string, string> = {
  Light: "轻装甲",
  Heavy: "重装甲",
  Special: "特殊装甲",
  Elastic: "弹性装甲",
};

const TEAM_LABELS: Record<string, string> = {
  FRONT: "前排",
  MIDDLE: "中排",
  BACK: "后排",
};

const BATTLE_LABELS: Record<string, string> = {
  STRIKER: "突击",
  SPECIAL: "特殊",
};

const STAT_LABELS: Record<string, string> = {
  max_hp: "HP",
  attack: "攻击",
  defense: "防御",
  healing: "治疗",
  accuracy: "命中",
  evasion: "回避",
  crit: "暴击",
  crit_res: "暴击抗性",
  crit_dmg: "暴击伤害",
  crit_dmg_res: "暴击伤害抗性",
  stability: "稳定",
  range: "射程",
  cc_power: "控制力",
  cc_res: "控制抗性",
  recovery_boost: "回复强化",
  mag_count: "弹匣",
};

function label(map: Record<string, string>, value: unknown): string {
  if (typeof value !== "string" || !value) return "";
  return map[value] ?? value;
}

function toNames(student: Record<string, any>): CharacterNames {
  return {
    cn: joinName(student.family_name_cn, student.given_name_cn),
    wiki: joinName(student.family_name, student.given_name),
    jp: joinName(student.family_name_jp, student.given_name_jp),
    kr: joinName(student.family_name_kr, student.given_name_kr),
    en: joinName(student.family_name_en, student.given_name_en),
    zh_tw: joinName(student.family_name_zh_tw, student.given_name_zh_tw),
  };
}

function buildSkins(student: Record<string, any>, indexEntry?: CharacterIndexEntry): SkinEntry[] {
  const list = Array.isArray(student.skin_list) ? student.skin_list : [];
  return list.map((skin: Record<string, any>) => {
    const known = indexEntry?.skins.find((item) => String(item.id) === String(skin.id));
    const isCurrent = String(skin.id) === String(student.id);
    return {
      id: Number(skin.id),
      cn: skin.skin_cn || skin.skin || known?.cn || "",
      jp: isCurrent ? student.skin_jp || known?.jp || "" : known?.jp || "",
      zh_tw: isCurrent ? student.skin_zh_tw || known?.zh_tw || "" : known?.zh_tw || "",
    };
  });
}

function buildProfile(student: Record<string, any>, schoolName: string): CharacterProfile {
  return {
    school: schoolName || SCHOOL_BY_ID.get(Number(student.school))?.nameCn || "",
    grade: student.grade ?? "",
    age: typeof student.age === "number" && student.age > 1 ? student.age : null,
    height: typeof student.height === "number" && student.height > 1 ? student.height : null,
    birthday: student.birthday ?? "",
    hobby: student.hobby ?? "",
    voiceJp: student.character_voice ?? "",
    voiceCn: student.character_voice_cn ?? "",
    illustrator: student.illustrator ?? "",
    designer: student.designer ?? "",
    weaponType: student.weapon_type ?? "",
    releaseJp: student.release_date ?? "",
    releaseCn: student.release_date_cn ?? "",
    releaseGlobal: student.release_date_global ?? "",
    momoTalk: student.momo_talk_signature ?? "",
  };
}

function asArray(value: unknown): any[] {
  return Array.isArray(value) ? value : [];
}

function buildSkill(skill: Record<string, any>): CharacterSkill {
  const info = asArray(skill.info);
  const first = info[0] ?? {};
  return {
    title: skill.title_cn || skill.title || "",
    cost: typeof first.cost === "number" ? first.cost : null,
    description: first.describe || "",
  };
}

function buildCombat(student: Record<string, any>): CharacterCombat {
  const data = asArray(student.character_datas)[0] ?? {};
  const skills = data.skill ?? {};
  const basic = asArray(data.basic)[0] ?? {};
  const stats: Record<string, number> = {};

  for (const [key, value] of Object.entries(basic)) {
    if (typeof value === "number") {
      stats[STAT_LABELS[key] ?? key] = value;
    }
  }

  return {
    rarity: typeof data.rarity === "number" ? data.rarity : null,
    role: label(ROLE_LABELS, data.type),
    attackType: label(ATTACK_LABELS, data.attack_attribute),
    defenseType: label(DEFENSE_LABELS, data.defensive_attributes),
    teamPosition: label(TEAM_LABELS, data.team_position),
    battlefieldPosition: label(BATTLE_LABELS, data.battlefield_position),
    adaptability: {
      outdoor: data.outdoor_adaptability ?? "",
      indoor: data.indoor_adaptability ?? "",
      street: data.street_adaptability ?? "",
    },
    weaponName: data.weapons?.name_cn || data.weapons?.name || "",
    weaponDescription: (data.weapons?.description_cn || data.weapons?.description || "")
      .replace(/<br\s*\/?>/gi, " ")
      .trim(),
    exSkills: asArray(skills.ex_skill).map(buildSkill),
    otherSkills: asArray(skills.passive_skill).map(buildSkill),
    stats,
  };
}

function buildVoices(student: Record<string, any>): CharacterVoice[] {
  const list = asArray(student.voice_cn).length > 0 ? asArray(student.voice_cn) : asArray(student.voice);
  return list.map((voice: Record<string, any>) => ({
    category: voice.category ?? "",
    description: voice.description ?? "",
    text: voice.text || voice.text_original || "",
    file: absoluteUrl(voice.file ?? ""),
  }));
}

function buildGallery(student: Record<string, any>): CharacterGalleryGroup[] {
  return asArray(student.gallery).map((group: Record<string, any>) => ({
    title: group.title ?? "",
    images: asArray(group.images).map((image: unknown) => absoluteUrl(String(image))),
  }));
}

function displayName(names: CharacterNames, skin: { cn: string }): string {
  const base = names.cn || names.wiki || names.jp;
  return skin.cn ? `${base}（${skin.cn}）` : base;
}

function toSearchResult(entry: CharacterIndexEntry): CharacterSearchResult {
  return {
    id: entry.id,
    displayName: entry.displayName,
    names: entry.names,
    aliases: entry.aliases,
    skin: entry.skin,
    school: entry.school.name,
    intro: entry.intro,
    url: entry.source,
    isCn: entry.isCn,
  };
}

function toSearchResultFromStudent(student: Record<string, any>): CharacterSearchResult {
  const names = toNames(student);
  const skin = {
    cn: student.skin_cn || student.skin || "",
    jp: student.skin_jp || "",
    zh_tw: student.skin_zh_tw || "",
  };
  const school = SCHOOL_BY_ID.get(Number(student.school));

  return {
    id: Number(student.id),
    displayName: displayName(names, skin),
    names,
    aliases: splitAliases(student.nick_name),
    skin,
    school: school?.nameCn || "",
    intro: student.introduction_cn || student.introduction || "",
    url: `https://kivo.wiki/data/character/${student.id}`,
    isCn: Boolean(student.is_install_cn),
  };
}

function normalize(value: string): string {
  return value
    .toLowerCase()
    .replace(/[\s·・._\-—–'’"“”()（）[\]【】]+/g, "");
}

function scoreEntry(entry: CharacterIndexEntry, query: string): number {
  const fullNames = [
    entry.names.cn,
    entry.names.wiki,
    entry.names.jp,
    entry.names.kr,
    entry.names.en,
    entry.names.zh_tw,
  ].filter(Boolean);
  const nameParts = fullNames.flatMap((name) => name.split(/\s+/).filter(Boolean));
  const extras = [
    entry.displayName,
    entry.skin.cn,
    entry.skin.jp,
    entry.skin.zh_tw,
    entry.school.name,
    ...entry.aliases,
  ].filter(Boolean);

  let best = 0;
  const consider = (field: string, allowSubstring: boolean) => {
    const value = normalize(field);
    if (!value) return;
    if (value === query) best = Math.max(best, 100);
    else if (value.startsWith(query)) best = Math.max(best, 80);
    else if (allowSubstring && value.includes(query)) best = Math.max(best, 60);
  };

  for (const name of fullNames) {
    // Spaced full names can match across word boundaries (e.g. "미카" inside
    // "이사미 카에데"), so only unspaced names allow substring matching.
    consider(name, !/\s/.test(name));
  }
  for (const part of nameParts) consider(part, true);
  for (const extra of extras) consider(extra, true);

  if (best > 0 && !entry.skin.cn) best += 2;
  return best;
}

export function searchLocal(query: string, limit: number): CharacterIndexEntry[] {
  const normalized = normalize(query);
  if (!normalized) return CHARACTERS.slice(0, limit);

  return CHARACTERS.map((entry) => ({ entry, score: scoreEntry(entry, normalized) }))
    .filter((item) => item.score > 0)
    .sort((a, b) => b.score - a.score || a.entry.id - b.entry.id)
    .slice(0, limit)
    .map((item) => item.entry);
}

export async function searchCharacters(query: string, limit = 5): Promise<CharacterSearchResult[]> {
  const safeLimit = Math.min(Math.max(limit, 1), 20);
  if (!query.trim()) {
    return CHARACTERS.slice(0, safeLimit).map(toSearchResult);
  }

  const local = searchLocal(query, safeLimit);
  const results = local.map(toSearchResult);

  if (results.length === 0) {
    try {
      const upstream = await searchUpstream(query, 20);
      const seen = new Set(results.map((item) => item.id));
      const candidates = upstream
        .filter((item) => item.type === "data_student")
        .slice(0, safeLimit);

      const hydrated = await Promise.all(
        candidates.map(async (item) => {
          const entry = BY_ID.get(Number(item.id));
          if (entry) return toSearchResult(entry);
          try {
            const student = await getStudent(Number(item.id));
            return toSearchResultFromStudent(student);
          } catch {
            return null;
          }
        }),
      );

      for (const result of hydrated) {
        if (!result || seen.has(result.id)) continue;
        results.push(result);
        seen.add(result.id);
        if (results.length >= safeLimit) break;
      }
    } catch {
      // The local index remains the source of truth if upstream search fails.
    }
  }

  return results.slice(0, safeLimit);
}

export function listCharacters(options: {
  school?: string;
  page?: number;
  limit?: number;
} = {}): { total: number; page: number; limit: number; results: CharacterSearchResult[] } {
  const page = Math.max(1, options.page ?? 1);
  const limit = Math.min(Math.max(options.limit ?? 20, 1), 100);
  const school = options.school?.trim();

  const filtered = school
    ? CHARACTERS.filter((entry) =>
        String(entry.school.id) === school || entry.school.name.includes(school),
      )
    : CHARACTERS;

  const start = (page - 1) * limit;
  return {
    total: filtered.length,
    page,
    limit,
    results: filtered.slice(start, start + limit).map(toSearchResult),
  };
}

export async function getCharacterDoc(id: number): Promise<CharacterDoc | null> {
  let student: Record<string, any>;
  try {
    student = await getStudent(id);
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    if (error instanceof UpstreamError && error.status === 404) return null;
    if (message.includes("不存在")) return null;
    throw error;
  }

  const indexEntry = BY_ID.get(id);
  const names = toNames(student);
  const skin = {
    cn: student.skin_cn || student.skin || "",
    jp: student.skin_jp || "",
    zh_tw: student.skin_zh_tw || "",
  };
  const schools = SCHOOL_BY_ID.get(Number(student.school));
  const profile = buildProfile(student, indexEntry?.school.name || schools?.nameCn || "");
  const combat = buildCombat(student);
  const characterId = asArray(student.character_datas)[0]?.character_id ?? "";
  const introCn = student.introduction_cn || "";
  const introWiki = student.introduction || "";
  const intro = introCn || introWiki;
  const introSource = introCn ? "cn" : introWiki ? "wiki" : "none";

  return {
    id: Number(student.id),
    displayName: displayName(names, skin),
    names,
    aliases: splitAliases(student.nick_name),
    skin,
    skins: buildSkins(student, indexEntry),
    profile,
    intro,
    introSource,
    lore: student.more || "",
    combat,
    voices: buildVoices(student),
    gallery: buildGallery(student),
    release: {
      cn: Boolean(student.is_install_cn),
      jp: Boolean(student.is_install),
      global: Boolean(student.is_install_global),
      cnDate: student.release_date_cn || null,
      jpDate: student.release_date || null,
      globalDate: student.release_date_global || null,
    },
    meta: {
      source: `https://kivo.wiki/data/character/${student.id}`,
      updatedAt: student.updated_at ? new Date(student.updated_at * 1000).toISOString() : null,
      isCn: Boolean(student.is_install_cn),
      cnMissing: !names.cn,
      characterId,
    },
  };
}

export function getAllCharacters(): CharacterIndexEntry[] {
  return CHARACTERS;
}
