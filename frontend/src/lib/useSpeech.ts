import { useCallback, useEffect, useRef, useState } from 'react';

/** Web Speech API 未在 TS DOM lib 中完整声明, 这里用最小接口描述实际用到的部分。 */
type Recognition = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start(): void;
  stop(): void;
  onresult: ((event: ResultEvent) => void) | null;
  onerror: ((event: { error?: string }) => void) | null;
  onend: (() => void) | null;
};
type ResultEvent = { resultIndex: number; results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }> };

function recognitionCtor(): (new () => Recognition) | null {
  if (typeof window === 'undefined') return null;
  const w = window as unknown as { SpeechRecognition?: new () => Recognition; webkitSpeechRecognition?: new () => Recognition };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

export function recognitionSupported(): boolean {
  return recognitionCtor() != null;
}

export function synthesisSupported(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window;
}

/**
 * 连续语音识别: 增量交付已确认文本 (onFinal 收到的是本帧新增片段),
 * 未确认的中间结果通过 interim 暴露给 UI。静默断流会自动续接, 用户点停止才真正结束。
 */
export function useSpeechRecognition(onFinal: (delta: string) => void) {
  const supported = recognitionSupported();
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState('');
  const [error, setError] = useState('');
  const recognitionRef = useRef<Recognition | null>(null);
  const keepListening = useRef(false);
  const deliver = useRef(onFinal);
  // 不能在渲染期间写 ref: 并发渲染下这次写入可能被丢弃, 回调会停在旧值上.
  useEffect(() => { deliver.current = onFinal; }, [onFinal]);

  const stop = useCallback(() => {
    keepListening.current = false;
    recognitionRef.current?.stop();
    setListening(false);
    setInterim('');
  }, []);

  const start = useCallback(() => {
    const Ctor = recognitionCtor();
    if (!Ctor) return;
    keepListening.current = true;
    let buffer = '';
    let delivered = '';
    const recognition = new Ctor();
    recognition.lang = 'zh-CN';
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.onresult = (event) => {
      let pending = '';
      for (let i = event.resultIndex; i < event.results.length; i += 1) {
        const result = event.results[i];
        if (result.isFinal) buffer += result[0].transcript;
        else pending += result[0].transcript;
      }
      setInterim(pending);
      if (buffer.length > delivered.length) {
        const delta = buffer.slice(delivered.length);
        delivered = buffer;
        deliver.current(delta);
      }
    };
    recognition.onerror = (event) => {
      keepListening.current = false;
      setListening(false);
      setInterim('');
      setError(event.error === 'not-allowed' ? '麦克风权限被拒绝，请允许后重试。' : '语音识别失败，可改用文字作答。');
    };
    recognition.onend = () => {
      // Chrome 在长静默后会自行结束; 用户没点停止就续接, 保证长回答不被截断。
      if (keepListening.current) {
        try { recognition.start(); return; } catch { /* 浏览器拒绝立即重启时交给用户重试 */ }
      }
      setListening(false);
      setInterim('');
    };
    recognitionRef.current = recognition;
    setError('');
    setListening(true);
    recognition.start();
  }, []);

  useEffect(() => () => {
    keepListening.current = false;
    recognitionRef.current?.stop();
  }, []);

  return { supported, listening, interim, error, start, stop };
}

/** 题目朗读: 返回 speak/cancel, 未提供合成能力时 speak 为空操作。 */
export function useSpeechSynthesis() {
  const cancel = useCallback(() => {
    if (!synthesisSupported()) return;
    window.speechSynthesis.cancel();
  }, []);

  const speak = useCallback((text: string) => {
    if (!synthesisSupported() || !text.trim()) return;
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-CN';
    utterance.rate = 1.05;
    window.speechSynthesis.speak(utterance);
  }, []);

  useEffect(() => () => { if (synthesisSupported()) window.speechSynthesis.cancel(); }, []);
  return { supported: synthesisSupported(), speak, cancel };
}
