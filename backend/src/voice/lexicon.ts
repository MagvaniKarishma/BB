/**
 * Vocabulary for Hindi (Devanagari + romanised "Hinglish"), Marathi and English
 * requirement notes. Keep entries lowercase; Devanagari is NFC-normalised when the
 * regexes are built, and nukta is treated as optional (हज़ार == हजार).
 */

/** Number words → value. Multi-word forms are listed explicitly. */
export const NUMBER_WORDS: Record<string, number> = {
  // Hinglish
  ek: 1, do: 2, teen: 3, tin: 3, char: 4, chaar: 4, paanch: 5, panch: 5, pach: 5, chhe: 6, chhah: 6, che: 6,
  saat: 7, sat: 7, aath: 8, nau: 9, das: 10, gyarah: 11, barah: 12, baara: 12, pandrah: 15, pandra: 15,
  atharah: 18, bees: 20, bis: 20, pachees: 25, pachchis: 25, pachis: 25, tees: 30, tis: 30, paintees: 35,
  chalis: 40, chaalis: 40, paintalis: 45, pachas: 50, pachaas: 50, pachpan: 55, saath: 60, pensath: 65,
  painsath: 65, sattar: 70, pachattar: 75, pachhattar: 75, assi: 80, pachasi: 85, nabbe: 90,
  pachanave: 95, sau: 100, dedh: 1.5, dhai: 2.5, dhaai: 2.5, adhai: 2.5,
  // Hindi (Devanagari)
  "एक": 1, "दो": 2, "तीन": 3, "चार": 4, "पांच": 5, "पाँच": 5, "छह": 6, "छः": 6, "सात": 7, "आठ": 8, "नौ": 9,
  "दस": 10, "ग्यारह": 11, "बारह": 12, "पंद्रह": 15, "अठारह": 18, "बीस": 20, "पच्चीस": 25, "तीस": 30,
  "पैंतीस": 35, "चालीस": 40, "पैंतालीस": 45, "पचास": 50, "पचपन": 55, "साठ": 60, "पैंसठ": 65, "सत्तर": 70,
  "पचहत्तर": 75, "अस्सी": 80, "पचासी": 85, "नब्बे": 90, "पचानवे": 95, "सौ": 100, "डेढ़": 1.5, "ढाई": 2.5,
  // Marathi (where different from Hindi)
  "दोन": 2, "पाच": 5, "सहा": 6, "नऊ": 9, "अकरा": 11, "बारा": 12, "पंधरा": 15, "वीस": 20, "पंचवीस": 25,
  "चाळीस": 40, "पन्नास": 50, "ऐंशी": 80, "नव्वद": 90, "शंभर": 100, "दीड": 1.5, "अडीच": 2.5,
  // English
  one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, twelve: 12,
  fifteen: 15, twenty: 20, "twenty five": 25, thirty: 30, "thirty five": 35, forty: 40, "forty five": 45,
  fifty: 50, sixty: 60, seventy: 70, "seventy five": 75, eighty: 80, ninety: 90, hundred: 100,
};

/** "saadhe teen" = 3.5, "sava do" = 2.25, "paune do" = 1.75 */
export const FRACTION_PREFIX: Record<string, number> = {
  sadhe: 0.5, saadhe: 0.5, sade: 0.5, "साढ़े": 0.5, "साडे": 0.5, "साढे": 0.5,
  sava: 0.25, sawa: 0.25, "सवा": 0.25,
  paune: -0.25, "पौने": -0.25, "पावणे": -0.25,
};

export const MONEY_UNITS: { words: string[]; multiplier: number }[] = [
  { words: ["k", "thousand", "hazar", "hazaar", "hajar", "hajaar", "hazzar", "हज़ार", "हजार"], multiplier: 1_000 },
  { words: ["lakh", "lakhs", "lac", "lacs", "laakh", "l", "लाख"], multiplier: 1_00_000 },
  { words: ["crore", "crores", "cr", "karod", "karor", "करोड़", "करोड", "कोटी", "koti"], multiplier: 1_00_00_000 },
];

/** Mumbai-region localities: canonical name → spoken/written variants. */
export const LOCALITIES: Record<string, string[]> = {
  Andheri: ["andheri", "अंधेरी", "अंधेरि"],
  Bandra: ["bandra", "बांद्रा", "बान्द्रा", "वांद्रे"],
  "Bandra Kurla Complex": ["bkc", "bandra kurla complex", "बीकेसी"],
  Khar: ["khar", "खार"],
  Santacruz: ["santacruz", "santa cruz", "सांताक्रूज़", "सांताक्रुज", "सांताक्रूझ"],
  "Vile Parle": ["vile parle", "vileparle", "parle", "विले पार्ले", "पार्ले"],
  Juhu: ["juhu", "जुहू"],
  Versova: ["versova", "वर्सोवा"],
  Lokhandwala: ["lokhandwala", "लोखंडवाला"],
  Oshiwara: ["oshiwara", "ओशिवरा"],
  Jogeshwari: ["jogeshwari", "जोगेश्वरी"],
  Goregaon: ["goregaon", "गोरेगांव", "गोरेगाँव", "गोरेगाव"],
  Malad: ["malad", "मलाड", "मालाड"],
  Kandivali: ["kandivali", "kandivli", "कांदिवली", "कांदिवली"],
  Borivali: ["borivali", "borivli", "बोरीवली", "बोरिवली"],
  Dahisar: ["dahisar", "दहिसर"],
  "Mira Road": ["mira road", "mira rd", "मीरा रोड"],
  Bhayandar: ["bhayandar", "bhayander", "भायंदर", "भाईंदर"],
  Vasai: ["vasai", "वसई"],
  Virar: ["virar", "विरार"],
  Powai: ["powai", "पवई"],
  Marol: ["marol", "मरोल"],
  Chandivali: ["chandivali", "चांदिवली"],
  Sakinaka: ["sakinaka", "saki naka", "साकीनाका"],
  Ghatkopar: ["ghatkopar", "ghatkoper", "घाटकोपर"],
  Vikhroli: ["vikhroli", "विक्रोली", "विखरोळी"],
  Kanjurmarg: ["kanjurmarg", "kanjur marg", "कांजुरमार्ग"],
  Bhandup: ["bhandup", "भांडुप"],
  Mulund: ["mulund", "मुलुंड"],
  Kurla: ["kurla", "कुर्ला"],
  Chembur: ["chembur", "चेंबूर", "चेम्बूर"],
  Sion: ["sion", "सायन", "शीव"],
  Wadala: ["wadala", "वडाला"],
  Dadar: ["dadar", "दादर"],
  Matunga: ["matunga", "माटुंगा"],
  Mahim: ["mahim", "माहिम", "माहीम"],
  Prabhadevi: ["prabhadevi", "प्रभादेवी"],
  Worli: ["worli", "वर्ली", "वरळी"],
  "Lower Parel": ["lower parel", "लोअर परेल"],
  Parel: ["parel", "परेल", "परळ"],
  Lalbaug: ["lalbaug", "लालबाग"],
  Byculla: ["byculla", "भायखला"],
  Tardeo: ["tardeo", "ताड़देव", "ताडदेव"],
  "Malabar Hill": ["malabar hill", "मलबार हिल"],
  Colaba: ["colaba", "कोलाबा"],
  Churchgate: ["churchgate", "चर्चगेट"],
  Kalina: ["kalina", "कलिना"],
  Thane: ["thane", "ठाणे", "थाणे"],
  "Navi Mumbai": ["navi mumbai", "नवी मुंबई"],
  Vashi: ["vashi", "वाशी"],
  Nerul: ["nerul", "नेरुल", "नेरूळ"],
  Kharghar: ["kharghar", "खारघर"],
  Panvel: ["panvel", "पनवेल"],
  Airoli: ["airoli", "ऐरोली"],
  Belapur: ["belapur", "बेलापुर", "बेलापूर"],
  Seawoods: ["seawoods", "सीवुड्स"],
  Dombivli: ["dombivli", "dombivali", "डोंबिवली"],
  Kalyan: ["kalyan", "कल्याण"],
};

/** Localities commonly split into East/West. */
export const DIRECTIONAL = new Set([
  "Andheri", "Bandra", "Khar", "Santacruz", "Vile Parle", "Jogeshwari", "Goregaon", "Malad", "Kandivali",
  "Borivali", "Dahisar", "Ghatkopar", "Vikhroli", "Kanjurmarg", "Bhandup", "Mulund", "Kurla", "Dadar",
  "Thane", "Bhayandar", "Vasai", "Virar", "Dombivli", "Kalyan",
]);
