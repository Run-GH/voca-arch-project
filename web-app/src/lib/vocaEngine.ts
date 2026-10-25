// ============================================================================
// VocaArch Web — Milestone 1 & 3: Supabase Client, Multi-POS Splitter & Dictionary/Audio Engine
// File: web-app/src/lib/vocaEngine.ts
// ============================================================================

import { createClient, SupabaseClient } from '@supabase/supabase-js';

export interface VocabEntry {
  id: string;
  book_title: string | null;
  chapter_name: string | null;
  word: string;
  phonetic: string | null;
  audio_url: string | null;
  part_of_speech: string;
  meaning_ko: string;
  example_sentence: string | null;
  attempt_count: number;
  wrong_count: number;
  error_rate: number;
  is_last_wrong: boolean;
  seq_order: number;
}

export type SortMode = 'WORD_ASC' | 'WORD_DESC' | 'RANDOM' | 'INPUT_ORDER' | 'ERROR_RATE_DESC';

export interface UserSettings {
  fontSizeScale: number;
  showPhonetic: boolean;
  showPos: boolean;
  showExample: boolean;
  flashcardAutoAdvanceSec: number;
  flashcardInitialSide: 'WORD' | 'MEANING';
}

export function createVocaSupabaseClient(url: string, anonKey: string): SupabaseClient {
  return createClient(url, anonKey);
}

/**
 * 오답/정답 체크 시 틀린 확률(error_rate)을 즉시 계산합니다.
 */
export function calculateUpdatedAttempt(entry: VocabEntry, isWrong: boolean): VocabEntry {
  const attempt_count = entry.attempt_count + 1;
  const wrong_count = isWrong ? entry.wrong_count + 1 : entry.wrong_count;
  const error_rate = Number(((wrong_count / attempt_count) * 100).toFixed(2));
  return {
    ...entry,
    attempt_count,
    wrong_count,
    error_rate,
    is_last_wrong: isWrong,
  };
}

/**
 * Free Dictionary API를 통해 이미지에 없는 발음기호(IPA) 및 원어민 음성 MP3 URL을 조회합니다.
 */
export async function fetchDictionaryMetadata(word: string): Promise<{ phonetic: string | null; audioUrl: string | null }> {
  try {
    const res = await fetch(`https://api.dictionaryapi.dev/api/v2/entries/en/${encodeURIComponent(word.trim().toLowerCase())}`);
    if (!res.ok) return { phonetic: null, audioUrl: null };
    const data = await res.json();
    const first = Array.isArray(data) ? data[0] : null;
    if (!first) return { phonetic: null, audioUrl: null };

    let phonetic: string | null = first.phonetic || null;
    let audioUrl: string | null = null;
    if (Array.isArray(first.phonetics)) {
      for (const p of first.phonetics) {
        if (!phonetic && p.text) phonetic = p.text;
        if (!audioUrl && p.audio) audioUrl = p.audio.startsWith('//') ? `https:${p.audio}` : p.audio;
      }
    }
    return { phonetic, audioUrl };
  } catch {
    return { phonetic: null, audioUrl: null };
  }
}

/**
 * 소리 재생 (1순위: 사용자 첨부 또는 사전 MP3 URL -> 2순위: Web Speech API TTS 폴백)
 */
export function playVocabSound(entry: VocabEntry): void {
  if (entry.audio_url) {
    const audio = new Audio(entry.audio_url);
    audio.play().catch(() => speakViaWebTts(entry.word));
    return;
  }
  speakViaWebTts(entry.word);
}

export function speakViaWebTts(word: string): void {
  if ('speechSynthesis' in window) {
    window.speechSynthesis.cancel();
    const utter = new SpeechSynthesisUtterance(word);
    utter.lang = 'en-US';
    utter.rate = 0.92;
    window.speechSynthesis.speak(utter);
  }
}
