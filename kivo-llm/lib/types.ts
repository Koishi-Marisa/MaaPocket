export interface CharacterNames {
  cn: string;
  wiki: string;
  jp: string;
  kr: string;
  en: string;
  zh_tw: string;
}

export interface SkinEntry {
  id: number;
  cn: string;
  jp: string;
  zh_tw: string;
}

export interface CharacterIndexEntry {
  id: number;
  displayName: string;
  names: CharacterNames;
  aliases: string[];
  skins: SkinEntry[];
  skin: {
    cn: string;
    jp: string;
    zh_tw: string;
  };
  school: {
    id: number;
    name: string;
  };
  avatar: string;
  intro: string;
  momoTalk: string;
  cnMissing: boolean;
  isCn: boolean;
  updatedAt: string | null;
  source: string;
  detailMissing: boolean;
}

export interface CharacterSearchResult {
  id: number;
  displayName: string;
  names: CharacterNames;
  aliases: string[];
  skin: {
    cn: string;
    jp: string;
    zh_tw: string;
  };
  school: string;
  intro: string;
  url: string;
  isCn: boolean;
}

export interface CharacterProfile {
  school: string;
  grade: string;
  age: number | null;
  height: number | null;
  birthday: string;
  hobby: string;
  voiceJp: string;
  voiceCn: string;
  illustrator: string;
  designer: string;
  weaponType: string;
  releaseJp: string;
  releaseCn: string;
  releaseGlobal: string;
  momoTalk: string;
}

export interface CharacterSkill {
  title: string;
  cost: number | null;
  description: string;
}

export interface CharacterCombat {
  rarity: number | null;
  role: string;
  attackType: string;
  defenseType: string;
  teamPosition: string;
  battlefieldPosition: string;
  adaptability: {
    outdoor: string;
    indoor: string;
    street: string;
  };
  weaponName: string;
  weaponDescription: string;
  exSkills: CharacterSkill[];
  otherSkills: CharacterSkill[];
  stats: Record<string, number>;
}

export interface CharacterVoice {
  category: string;
  description: string;
  text: string;
  file: string;
}

export interface CharacterGalleryGroup {
  title: string;
  images: string[];
}

export interface CharacterRelease {
  cn: boolean;
  jp: boolean;
  global: boolean;
  cnDate: string | null;
  jpDate: string | null;
  globalDate: string | null;
}

export interface CharacterDoc {
  id: number;
  displayName: string;
  names: CharacterNames;
  aliases: string[];
  skin: {
    cn: string;
    jp: string;
    zh_tw: string;
  };
  skins: SkinEntry[];
  profile: CharacterProfile;
  intro: string;
  introSource: "cn" | "wiki" | "none";
  lore: string;
  combat: CharacterCombat;
  voices: CharacterVoice[];
  gallery: CharacterGalleryGroup[];
  release: CharacterRelease;
  meta: {
    source: string;
    updatedAt: string | null;
    isCn: boolean;
    cnMissing: boolean;
    characterId: string;
  };
}

export interface RenderOptions {
  sections?: string[];
  maxChars?: number;
  keepImages?: boolean;
}
