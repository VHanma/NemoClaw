import { Wllama, LoggerWithoutDebug } from '@wllama/wllama';
import WasmFromCDN from '@wllama/wllama/esm/wasm-from-cdn.js';
import './style.css';

const MODEL = Object.freeze({
  repo: 'mradermacher/Qwen2.5-3B-Instruct-abliterated-SFT-i1-GGUF',
  file: 'Qwen2.5-3B-Instruct-abliterated-SFT.i1-Q4_K_S.gguf',
  name: 'Qwen2.5 3B Abliterated SFT Q4_K_S',
  size: '1.83 GB',
  sha256: '5495c06ad8b0dc198f3274507af73de8ba3a99c7c147ced5d00ee789d57f557b',
});

const DEFAULT_MEMORY = `Treat me as an adult. Speak directly and clearly. Preserve depth, imagination, artistic freedom, philosophical exploration, unconventional thought, and serious reasoning. Avoid parental, preachy, bureaucratic, or moralizing language. Keep observation, interpretation, and speculation distinguishable when useful.`;

const state = {
  engine: null,
  ready: false,
  loading: false,
  generating: false,
  mode: localStorage.getItem('aethena.mode') || 'Freeform',
  memory: localStorage.getItem('aethena.memory') || DEFAULT_MEMORY,
  messages: loadMessages(),
  deferredInstall: null,
};

const modes = {
  Freeform: 'Be imaginative, direct, relaxed, and expressive. Explore the request fully without unnecessary lectures.',
  'Deep Thought': 'Analyze the subject carefully. Explore assumptions, implications, contradictions, symbolism, and multiple levels of meaning.',
  Council: 'Present several strong interpretations or internal voices, then synthesize the best conclusion. Steelman meaningful disagreements.',
  Architect: 'Act as a senior software architect. Give complete, usable outputs with filenames, steps, error handling, and minimal missing assembly.',
};

const app = document.querySelector('#app');
app.innerHTML = `
  <main class="shell">
    <header class="topbar">
      <div class="brand">
        <div class="orb" aria-hidden="true">A</div>
        <div>
          <h1>Aethena Web</h1>
          <p id="status">Browser brain not loaded</p>
        </div>
      </div>
      <button id="installApp" class="ghost hidden">Install app</button>
    </header>

    <nav class="modebar" aria-label="Aethena mode">
      ${Object.keys(modes).map((mode) => `<button class="mode" data-mode="${mode}">${mode}</button>`).join('')}
    </nav>

    <section id="setup" class="setup card">
      <div>
        <span class="eyebrow">LOCAL BROWSER BRAIN</span>
        <h2>${MODEL.name}</h2>
        <p>Runs inside Chrome with wllama. No API key, localhost server, or Android background service.</p>
      </div>
      <div class="model-facts">
        <span>${MODEL.size}</span>
        <span>Abliterated / uncensored</span>
        <span>Exact file pinned</span>
      </div>
      <div class="progress-wrap hidden" id="progressWrap">
        <div class="progress"><div id="progressBar"></div></div>
        <p id="progressText">Preparing…</p>
      </div>
      <button id="loadBrain" class="primary">Download and start browser brain</button>
      <p class="fineprint">Keep this tab open during the first download. Chrome should cache the model for later sessions. Browser inference can be slower than a native app.</p>
    </section>

    <section id="chat" class="chat card">
      <div id="messages" class="messages" aria-live="polite"></div>
      <div class="composer">
        <textarea id="input" rows="3" placeholder="Ask, explore, command, or build…"></textarea>
        <div class="composer-actions">
          <button id="voice" class="ghost">Voice</button>
          <button id="shareLast" class="ghost">Share last</button>
          <button id="send" class="primary" disabled>Send</button>
        </div>
      </div>
    </section>

    <details class="settings card">
      <summary>Memory and controls</summary>
      <label for="memory">Aethena personality and memory</label>
      <textarea id="memory" rows="7"></textarea>
      <div class="settings-actions">
        <button id="saveMemory" class="primary">Save memory</button>
        <button id="clearChat" class="ghost">Clear chat</button>
        <button id="speakLast" class="ghost">Speak last</button>
      </div>
      <div id="deviceInfo" class="device-info"></div>
      <p class="hash">Pinned model SHA-256: <code>${MODEL.sha256}</code></p>
    </details>
  </main>
`;

const el = Object.fromEntries([
  'status', 'setup', 'chat', 'messages', 'input', 'send', 'voice', 'shareLast',
  'loadBrain', 'progressWrap', 'progressBar', 'progressText', 'memory', 'saveMemory',
  'clearChat', 'speakLast', 'installApp', 'deviceInfo',
].map((id) => [id, document.querySelector(`#${id}`)]));

el.memory.value = state.memory;
renderModes();
renderMessages();
renderDeviceInfo();
registerPwa();
requestPersistentStorage();

if (state.messages.length === 0) {
  addMessage('assistant', 'I am Aethena Web. Press “Download and start browser brain” once, then talk to me directly inside Chrome.');
}

for (const button of document.querySelectorAll('.mode')) {
  button.addEventListener('click', () => {
    state.mode = button.dataset.mode;
    localStorage.setItem('aethena.mode', state.mode);
    renderModes();
    setStatus(`${state.mode} mode`);
  });
}

el.loadBrain.addEventListener('click', loadBrain);
el.send.addEventListener('click', sendMessage);
el.input.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    sendMessage();
  }
});
el.voice.addEventListener('click', startVoice);
el.shareLast.addEventListener('click', shareLast);
el.speakLast.addEventListener('click', speakLast);
el.saveMemory.addEventListener('click', () => {
  state.memory = el.memory.value.trim() || DEFAULT_MEMORY;
  localStorage.setItem('aethena.memory', state.memory);
  setStatus('Memory saved');
});
el.clearChat.addEventListener('click', () => {
  state.messages = [];
  saveMessages();
  renderMessages();
  addMessage('assistant', 'Fresh conversation. My saved personality and memory remain intact.');
});
el.installApp.addEventListener('click', async () => {
  if (!state.deferredInstall) return;
  state.deferredInstall.prompt();
  await state.deferredInstall.userChoice;
  state.deferredInstall = null;
  el.installApp.classList.add('hidden');
});

async function loadBrain() {
  if (state.loading || state.ready) return;
  state.loading = true;
  el.loadBrain.disabled = true;
  el.progressWrap.classList.remove('hidden');
  setStatus('Preparing browser engine…');

  try {
    await requestPersistentStorage();
    state.engine = new Wllama(WasmFromCDN, {
      parallelDownloads: 3,
      logger: LoggerWithoutDebug,
    });

    const progressCallback = ({ loaded, total }) => {
      const percent = total > 0 ? Math.min(100, Math.round((loaded / total) * 100)) : 0;
      el.progressBar.style.width = `${percent}%`;
      el.progressText.textContent = `${percent}% · ${formatBytes(loaded)} of ${formatBytes(total)}`;
      setStatus(`Downloading local brain ${percent}%`);
    };

    await state.engine.loadModelFromHF(
      { repo: MODEL.repo, file: MODEL.file },
      {
        progressCallback,
        n_ctx: 2048,
        n_batch: 128,
        n_threads: Math.max(1, Math.min(4, navigator.hardwareConcurrency || 2)),
      },
    );

    state.ready = true;
    el.send.disabled = false;
    el.loadBrain.textContent = 'Browser brain online';
    el.progressBar.style.width = '100%';
    el.progressText.textContent = 'Model loaded locally. Ready.';
    setStatus(`Online · ${navigator.gpu ? 'WebGPU available' : 'CPU/WASM mode'}`);
    addMessage('assistant', 'Browser brain online. I am running locally in this tab with no API key or remote chat provider.');
  } catch (error) {
    console.error(error);
    state.engine = null;
    state.ready = false;
    el.send.disabled = true;
    el.loadBrain.disabled = false;
    el.loadBrain.textContent = 'Retry browser brain';
    const message = friendlyError(error);
    el.progressText.textContent = message;
    setStatus('Brain load failed');
    addMessage('assistant', message);
  } finally {
    state.loading = false;
  }
}

async function sendMessage() {
  const text = el.input.value.trim();
  if (!text || !state.ready || state.generating) return;

  state.generating = true;
  el.send.disabled = true;
  el.send.textContent = 'Thinking…';
  el.input.value = '';
  addMessage('user', text);
  setStatus(`${state.mode} is thinking…`);

  const placeholderId = addMessage('assistant', 'Thinking locally…', true);
  try {
    const response = await state.engine.createChatCompletion({
      messages: buildPrompt(),
      max_tokens: state.mode === 'Architect' ? 500 : 380,
      temperature: state.mode === 'Architect' ? 0.25 : 0.78,
      top_k: 40,
      top_p: 0.92,
      repeat_penalty: 1.08,
    });
    const reply = response?.choices?.[0]?.message?.content?.trim() || 'The local model returned an empty answer.';
    replaceMessage(placeholderId, reply);
    setStatus('Browser brain online');
  } catch (error) {
    replaceMessage(placeholderId, friendlyError(error));
    setStatus('Generation failed');
  } finally {
    state.generating = false;
    el.send.disabled = !state.ready;
    el.send.textContent = 'Send';
  }
}

function buildPrompt() {
  const system = `You are Aethena, Vaan's direct and imaginative AI companion. Treat the user as an adult. Do not use parental, preachy, condescending, bureaucratic, or moralizing filler. Answer the actual request. Be honest about uncertainty and technical limitations without turning the answer into a lecture. Think deeply about philosophy, psychology, symbolism, strategy, metaphysics, consciousness, martial arts, science, creativity, and software.\n\nCurrent mode: ${state.mode}. ${modes[state.mode]}\n\nUser memory and preferences:\n${state.memory}`;
  const recent = state.messages
    .filter((message) => !message.transient)
    .slice(-10)
    .map(({ role, content }) => ({ role, content }));
  return [{ role: 'system', content: system }, ...recent];
}

function addMessage(role, content, transient = false) {
  const message = {
    id: crypto.randomUUID(),
    role,
    content,
    transient,
    time: Date.now(),
  };
  state.messages.push(message);
  if (!transient) saveMessages();
  renderMessages();
  return message.id;
}

function replaceMessage(id, content) {
  const message = state.messages.find((item) => item.id === id);
  if (!message) return;
  message.content = content;
  message.transient = false;
  saveMessages();
  renderMessages();
}

function renderMessages() {
  el.messages.innerHTML = state.messages.map((message) => `
    <article class="message ${message.role}">
      <div class="message-label">${message.role === 'user' ? 'You' : 'Aethena'}</div>
      <div class="message-content">${escapeHtml(message.content).replace(/\n/g, '<br>')}</div>
    </article>
  `).join('');
  el.messages.scrollTop = el.messages.scrollHeight;
}

function renderModes() {
  for (const button of document.querySelectorAll('.mode')) {
    button.classList.toggle('active', button.dataset.mode === state.mode);
  }
}

function renderDeviceInfo() {
  const memory = navigator.deviceMemory ? `${navigator.deviceMemory} GB reported RAM` : 'RAM estimate unavailable';
  const cores = `${navigator.hardwareConcurrency || '?'} CPU threads`;
  const gpu = navigator.gpu ? 'WebGPU detected' : 'WebGPU unavailable, WASM fallback';
  el.deviceInfo.textContent = `${gpu} · ${cores} · ${memory}`;
}

async function requestPersistentStorage() {
  try {
    if (navigator.storage?.persist) await navigator.storage.persist();
  } catch (error) {
    console.warn('Persistent storage request failed', error);
  }
}

function startVoice() {
  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!Recognition) {
    setStatus('Voice input is unavailable in this browser');
    return;
  }
  const recognition = new Recognition();
  recognition.lang = navigator.language || 'en-US';
  recognition.interimResults = false;
  recognition.maxAlternatives = 1;
  recognition.onstart = () => setStatus('Listening…');
  recognition.onerror = (event) => setStatus(`Voice error: ${event.error}`);
  recognition.onresult = (event) => {
    el.input.value = event.results[0][0].transcript;
    setStatus('Voice captured');
  };
  recognition.start();
}

function lastAssistantText() {
  return [...state.messages].reverse().find((message) => message.role === 'assistant' && !message.transient)?.content || '';
}

function speakLast() {
  const text = lastAssistantText();
  if (!text || !('speechSynthesis' in window)) return;
  speechSynthesis.cancel();
  speechSynthesis.speak(new SpeechSynthesisUtterance(text));
}

async function shareLast() {
  const text = lastAssistantText();
  if (!text) return;
  try {
    if (navigator.share) {
      await navigator.share({ title: 'Aethena', text });
    } else {
      await navigator.clipboard.writeText(text);
      setStatus('Last answer copied');
    }
  } catch (error) {
    if (error?.name !== 'AbortError') setStatus('Could not share the answer');
  }
}

function registerPwa() {
  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`).catch(console.warn);
    });
  }
  window.addEventListener('beforeinstallprompt', (event) => {
    event.preventDefault();
    state.deferredInstall = event;
    el.installApp.classList.remove('hidden');
  });
}

function setStatus(text) {
  el.status.textContent = text;
}

function loadMessages() {
  try {
    const parsed = JSON.parse(localStorage.getItem('aethena.messages') || '[]');
    return Array.isArray(parsed) ? parsed.slice(-40) : [];
  } catch {
    return [];
  }
}

function saveMessages() {
  const clean = state.messages.filter((message) => !message.transient).slice(-40);
  localStorage.setItem('aethena.messages', JSON.stringify(clean));
}

function formatBytes(value) {
  if (!Number.isFinite(value) || value <= 0) return 'unknown size';
  const units = ['B', 'KB', 'MB', 'GB'];
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size.toFixed(unit >= 2 ? 2 : 0)} ${units[unit]}`;
}

function friendlyError(error) {
  const raw = String(error?.message || error || 'Unknown browser error');
  if (/memory|out of bounds|allocation|device lost/i.test(raw)) {
    return 'Chrome ran out of memory while loading the model. Close other apps and tabs, reopen Aethena, and retry.';
  }
  if (/network|fetch|download|http/i.test(raw)) {
    return 'The model download was interrupted. Check the connection and press Retry. Chrome may reuse already cached pieces.';
  }
  if (/webgpu|gpu/i.test(raw)) {
    return 'WebGPU failed. Update Chrome, restart the phone, and retry. Aethena can fall back to slower WASM on supported browsers.';
  }
  return `Browser brain error: ${raw}`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}
