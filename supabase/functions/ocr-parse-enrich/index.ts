// ============================================================================
// VocaArch — Supabase Edge Function: ocr-parse-enrich
// File: supabase/functions/ocr-parse-enrich/index.ts
// Description:
//   1. 이미지(Base64/Multipart) 또는 OCR 텍스트를 입력받아 영단어, 발음기호, 품사, 한글 뜻, 예문을 추출
//   2. 영단어 1개가 여러 품사/의미를 가질 경우 품사(n., v., adj., adv.)별로 행(Row)을 자동 분리
//   3. 발음기호(phonetic)나 음성(audio_url)이 누락된 경우 Free Dictionary API를 조회해 자동 보강
// ============================================================================

import { serve } from "https://deno.land/std@0.192.0/http/server.ts";

export interface ParsedVocabRow {
  book_title: string | null;
  chapter_name: string | null;
  word: string;
  phonetic: string | null;
  audio_url: string | null;
  part_of_speech: string;
  meaning_ko: string;
  example_sentence: string | null;
}

const POS_MARKERS = /(?:^|\s|\/|;|,)(n\.|v\.|vt\.|vi\.|adj\.|a\.|adv\.|ad\.|prep\.|conj\.|pron\.|명\.|동\.|형\.|부\.)\s*/gi;

const POS_NORMALIZE_MAP: Record<string, string> = {
  "n.": "n.", "명.": "n.",
  "v.": "v.", "vt.": "v.", "vi.": "v.", "동.": "v.",
  "adj.": "adj.", "a.": "adj.", "형.": "adj.",
  "adv.": "adv.", "ad.": "adv.", "부.": "adv.",
  "prep.": "prep.", "conj.": "conj.", "pron.": "pron."
};

/**
 * Free Dictionary API를 호출해 누락된 발음기호(IPA) 및 원어민 MP3 URL을 가져옵니다.
 */
async function enrichFromDictionaryApi(word: string): Promise<{ phonetic: string | null; audioUrl: string | null }> {
  try {
    const res = await fetch(`https://api.dictionaryapi.dev/api/v2/entries/en/${encodeURIComponent(word.trim().toLowerCase())}`);
    if (!res.ok) return { phonetic: null, audioUrl: null };
    const data = await res.json();
    if (!Array.isArray(data) || data.length === 0) return { phonetic: null, audioUrl: null };

    const entry = data[0];
    let phonetic: string | null = entry.phonetic || null;
    let audioUrl: string | null = null;

    if (Array.isArray(entry.phonetics)) {
      for (const p of entry.phonetics) {
        if (!phonetic && p.text) phonetic = p.text;
        if (!audioUrl && p.audio && p.audio.trim().length > 0) {
          audioUrl = p.audio.startsWith("//") ? `https:${p.audio}` : p.audio;
        }
      }
    }
    return { phonetic, audioUrl };
  } catch (_e) {
    return { phonetic: null, audioUrl: null };
  }
}

/**
 * 한 줄의 OCR 텍스트에서 다품사(예: "present /preznt/ n. 선물 v. 제출하다")를 감지해
 * 품사별 독립 행으로 분리합니다.
 */
export function splitMultiPosLine(
  rawLine: string,
  bookTitle: string | null,
  chapterName: string | null
): ParsedVocabRow[] {
  const cleaned = rawLine.trim();
  if (!cleaned) return [];

  // 예문이 다음 줄이나 파이프(|) 뒤에 있는 경우 분리
  const [mainPart, examplePart] = cleaned.split(/\s*\|\s*|\s*Ex:\s*/i);

  // 1. 영단어 및 선택적 발음기호 추출: 예) "abandon [əˈbændən] v. 포기하다"
  const headMatch = mainPart.match(/^([a-zA-Z\-\s]+?)(?:\s+([\/\[][^\/\]\n]+[\/\]]))?\s+(.+)$/);
  if (!headMatch) return [];

  const word = headMatch[1].trim();
  const extractedPhonetic = headMatch[2] ? headMatch[2].trim() : null;
  const restMeanings = headMatch[3].trim();

  // 2. 다품사 마커 탐색 및 분리
  const matches = [...restMeanings.matchAll(POS_MARKERS)];
  const rows: ParsedVocabRow[] = [];

  if (matches.length === 0) {
    rows.push({
      book_title: bookTitle || null,
      chapter_name: chapterName || null,
      word,
      phonetic: extractedPhonetic,
      audio_url: null,
      part_of_speech: "n.",
      meaning_ko: restMeanings,
      example_sentence: examplePart ? examplePart.trim() : null,
    });
  } else {
    for (let i = 0; i < matches.length; i++) {
      const rawPos = matches[i][1].toLowerCase();
      const normalizedPos = POS_NORMALIZE_MAP[rawPos] || rawPos;
      const startIdx = (matches[i].index ?? 0) + matches[i][0].length;
      const endIdx = i + 1 < matches.length ? (matches[i + 1].index ?? restMeanings.length) : restMeanings.length;
      const meaningSegment = restMeanings.slice(startIdx, endIdx).replace(/[\/;,\s]+$/, "").trim();

      if (meaningSegment) {
        rows.push({
          book_title: bookTitle || null,
          chapter_name: chapterName || null,
          word,
          phonetic: extractedPhonetic,
          audio_url: null,
          part_of_speech: normalizedPos,
          meaning_ko: meaningSegment,
          example_sentence: examplePart ? examplePart.trim() : null,
        });
      }
    }
  }

  return rows;
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", {
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
      },
    });
  }

  try {
    const { rawText, bookTitle = null, chapterName = null } = await req.json();
    const lines = String(rawText || "").split(/\r?\n/);
    const parsedRows: ParsedVocabRow[] = [];

    for (const line of lines) {
      const splitRows = splitMultiPosLine(line, bookTitle, chapterName);
      parsedRows.push(...splitRows);
    }

    // 발음기호 또는 오디오가 없는 단어들에 대해 Dictionary API 보강 실행
    const uniqueWords = [...new Set(parsedRows.map((r) => r.word.toLowerCase()))];
    const enrichmentCache = new Map<string, { phonetic: string | null; audioUrl: string | null }>();

    await Promise.all(
      uniqueWords.map(async (w) => {
        const info = await enrichFromDictionaryApi(w);
        enrichmentCache.set(w, info);
      })
    );

    const enrichedRows = parsedRows.map((row) => {
      const extra = enrichmentCache.get(row.word.toLowerCase());
      return {
        ...row,
        phonetic: row.phonetic || extra?.phonetic || null,
        audio_url: row.audio_url || extra?.audioUrl || null,
      };
    });

    return new Response(JSON.stringify({ rows: enrichedRows }), {
      headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
    });
  } catch (err) {
    return new Response(JSON.stringify({ error: String(err) }), {
      status: 400,
      headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
    });
  }
});
