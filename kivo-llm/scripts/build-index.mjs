import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const API = "https://api.kivo.wiki/api/v1";
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const CONCURRENCY = 3;
const UA = "kivo-llm-gateway/0.1 (+https://kivo.wiki; character index)";

async function getJson(path, attempt = 0) {
  try {
    const res = await fetch(`${API}${path}`, {
      headers: { "user-agent": UA, accept: "application/json" },
      signal: AbortSignal.timeout(15000),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const body = await res.json();
    if (!body?.success) throw new Error(body?.message || "upstream error");
    return body.data;
  } catch (error) {
    if (attempt < 2) {
      await new Promise((r) => setTimeout(r, 400 * (attempt + 1)));
      return getJson(path, attempt + 1);
    }
    throw error;
  }
}

async function mapLimit(items, limit, fn) {
  const results = new Array(items.length);
  let cursor = 0;
  async function worker() {
    while (cursor < items.length) {
      const index = cursor++;
      results[index] = await fn(items[index], index);
      await new Promise((r) => setTimeout(r, 80));
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

function splitAliases(value) {
  if (!value) return [];
  return value
    .split(/[,，、;；]/)
    .map((s) => s.trim())
    .filter(Boolean);
}

function nameOf(student, lang) {
  const family = student[`family_name${lang}`] ?? "";
  const given = student[`given_name${lang}`] ?? "";
  return `${family} ${given}`.trim();
}

function displayName(names, skinCn) {
  const base = names.cn || names.wiki || names.jp || `#${names.id}`;
  return skinCn ? `${base}（${skinCn}）` : base;
}

function buildSkins(student) {
  const list = Array.isArray(student.skin_list) ? student.skin_list : [];
  return list.map((skin) => {
    const isCurrent = String(skin.id) === String(student.id);
    return {
      id: skin.id,
      cn: skin.skin_cn || skin.skin || "",
      jp: isCurrent ? student.skin_jp || "" : "",
      zh_tw: isCurrent ? student.skin_zh_tw || "" : "",
    };
  });
}

async function main() {
  console.log("Fetching CN character list...");
  const first = await getJson("/data/students/?is_install_cn=true&page=1&page_size=100");
  const maxPage = first.max_page ?? 1;
  const list = [...first.students];
  for (let page = 2; page <= maxPage; page++) {
    const data = await getJson(`/data/students/?is_install_cn=true&page=${page}&page_size=100`);
    list.push(...data.students);
  }
  console.log(`List: ${list.length} entries`);

  console.log("Fetching schools...");
  const schoolFirst = await getJson("/data/schools/?page=1&page_size=100");
  const schools = [...schoolFirst.school];
  for (let page = 2; page <= (schoolFirst.max_page ?? 1); page++) {
    const data = await getJson(`/data/schools/?page=${page}&page_size=100`);
    schools.push(...data.school);
  }
  const schoolMap = Object.fromEntries(
    schools.map((school) => [
      String(school.id),
      { id: school.id, name: school.name, nameCn: school.name_cn || school.name },
    ]),
  );
  console.log(`Schools: ${schools.length}`);

  console.log(`Fetching ${list.length} character details (concurrency ${CONCURRENCY})...`);
  let done = 0;
  const detailById = new Map();
  const entries = await mapLimit(list, CONCURRENCY, async (item) => {
    let student = item;
    let detailMissing = false;
    try {
      student = await getJson(`/data/students/${item.id}`);
      detailById.set(String(student.id), student);
    } catch {
      detailMissing = true;
    }
    done++;
    if (done % 25 === 0 || done === list.length) console.log(`  ${done}/${list.length}`);

    const names = {
      cn: nameOf(student, "_cn"),
      wiki: nameOf(student, ""),
      jp: nameOf(student, "_jp"),
      kr: nameOf(student, "_kr"),
      en: nameOf(student, "_en"),
      zh_tw: nameOf(student, "_zh_tw"),
    };
    const skinCn = student.skin_cn || student.skin || "";
    const school = schoolMap[String(student.school)] ?? null;
    const intro = (student.introduction_cn || student.introduction || "")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, 140);

    const skins = buildSkins(student).map((skin) => {
      const detail = detailById.get(String(skin.id));
      return {
        id: skin.id,
        cn: skin.cn || detail?.skin_cn || "",
        jp: detail?.skin_jp || "",
        zh_tw: detail?.skin_zh_tw || "",
      };
    });

    return {
      id: student.id,
      displayName: displayName(names, skinCn),
      names,
      aliases: splitAliases(student.nick_name),
      skins,
      skin: {
        cn: skinCn,
        jp: student.skin_jp || "",
        zh_tw: student.skin_zh_tw || "",
      },
      school: school
        ? { id: school.id, name: school.nameCn }
        : { id: student.school, name: "" },
      avatar: (student.avatar || "").replace(/^\/\//, "https://"),
      intro,
      momoTalk: student.momo_talk_signature || "",
      cnMissing: !names.cn,
      isCn: Boolean(student.is_install_cn),
      updatedAt: student.updated_at
        ? new Date(student.updated_at * 1000).toISOString()
        : null,
      source: `https://kivo.wiki/data/character/${student.id}`,
      detailMissing,
    };
  });

  await mkdir(resolve(ROOT, "data"), { recursive: true });
  await writeFile(resolve(ROOT, "data/characters.json"), JSON.stringify(entries));
  await writeFile(resolve(ROOT, "data/schools.json"), JSON.stringify(Object.values(schoolMap)));
  console.log(`Wrote ${entries.length} characters and ${schools.length} schools.`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
