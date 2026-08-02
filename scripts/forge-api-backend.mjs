#!/usr/bin/env node

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) continue;
    const eq = a.indexOf('=');
    if (eq > 2) out[a.slice(2, eq)] = a.slice(eq + 1);
    else out[a.slice(2)] = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
  }
  return out;
}

function required(name, value) {
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}

async function postJson(url, headers, body, timeoutMs) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...headers },
      body: JSON.stringify(body),
      signal: ctl.signal
    });
    const text = await res.text();
    let data;
    try { data = JSON.parse(text); } catch { data = null; }
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}: ${text.slice(0, 800)}`);
    return data ?? text;
  } finally {
    clearTimeout(timer);
  }
}

function extractResponses(data) {
  if (typeof data?.output_text === 'string' && data.output_text.trim()) return data.output_text.trim();
  const parts = [];
  for (const item of data?.output || []) {
    for (const content of item?.content || []) {
      if (typeof content?.text === 'string') parts.push(content.text);
      else if (typeof content?.output_text === 'string') parts.push(content.output_text);
    }
  }
  return parts.join('\n').trim();
}

function extractAnthropic(data) {
  return (data?.content || []).map(x => x?.text).filter(Boolean).join('\n').trim();
}

function extractGemini(data) {
  const parts = data?.candidates?.[0]?.content?.parts || [];
  return parts.map(x => x?.text).filter(Boolean).join('\n').trim();
}

async function main() {
  const a = parseArgs(process.argv.slice(2));
  const provider = String(a.provider || '').toLowerCase();
  const model = required('model', a.model);
  const prompt = required('prompt', a.prompt);
  const timeoutMs = Math.max(1000, Math.min(900000, Number(a.timeout || 240000)));
  const effort = String(a.effort || 'high');
  let output = '';

  if (provider === 'openai' || provider === 'xai') {
    const keyEnv = provider === 'openai' ? 'OPENAI_API_KEY' : 'XAI_API_KEY';
    const key = required(keyEnv, process.env[keyEnv]);
    const base = provider === 'openai' ? 'https://api.openai.com/v1' : 'https://api.x.ai/v1';
    const body = { model, input: prompt };
    if (provider === 'openai') body.reasoning = { effort };
    const data = await postJson(`${base}/responses`, { authorization: `Bearer ${key}` }, body, timeoutMs);
    output = extractResponses(data);
  } else if (provider === 'anthropic') {
    const key = required('ANTHROPIC_API_KEY', process.env.ANTHROPIC_API_KEY);
    const data = await postJson('https://api.anthropic.com/v1/messages', {
      'x-api-key': key,
      'anthropic-version': '2023-06-01'
    }, {
      model,
      max_tokens: Math.max(1024, Math.min(64000, Number(a['max-tokens'] || 16000))),
      messages: [{ role: 'user', content: prompt }]
    }, timeoutMs);
    output = extractAnthropic(data);
  } else if (provider === 'gemini') {
    const key = required('GEMINI_API_KEY', process.env.GEMINI_API_KEY);
    const data = await postJson(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(key)}`, {}, {
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: { thinkingConfig: { thinkingLevel: effort === 'max' || effort === 'xhigh' ? 'high' : effort } }
    }, timeoutMs);
    output = extractGemini(data);
  } else if (provider === 'openai-compatible') {
    const baseEnv = String(a['base-url-env'] || 'FORGE_COMPATIBLE_BASE_URL');
    const keyEnv = String(a['api-key-env'] || 'FORGE_COMPATIBLE_API_KEY');
    const base = required(baseEnv, process.env[baseEnv]).replace(/\/$/, '');
    const key = process.env[keyEnv] || '';
    const data = await postJson(`${base}/chat/completions`, key ? { authorization: `Bearer ${key}` } : {}, {
      model,
      messages: [{ role: 'user', content: prompt }]
    }, timeoutMs);
    output = data?.choices?.[0]?.message?.content?.trim?.() || '';
  } else {
    throw new Error(`Unsupported provider: ${provider}`);
  }

  if (!output) throw new Error(`${provider}/${model} returned no text output.`);
  process.stdout.write(output + (output.endsWith('\n') ? '' : '\n'));
}

main().catch(error => {
  console.error(`FORGE API backend failed: ${error?.message || error}`);
  process.exitCode = 1;
});
