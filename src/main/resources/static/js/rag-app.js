'use strict';
/* ═══════════════════════════════════════════════════════════════
   rag-app.js — orchestrates all viz modules + SSE + state
   ═══════════════════════════════════════════════════════════════ */

// ── State ─────────────────────────────────────────────────────
const state = {
  configured: false,
  docId:      null,
  indexing:   false,
  querying:   false,
};

// ── Module instances (initialised in init()) ─────────────────
let vlessFlow, vectFlow;
let treeViz;
let chunkViz;
let vlessInspector, vectInspector;

// ── Init ──────────────────────────────────────────────────────
function init() {
  // Flow diagrams
  vlessFlow = new FlowDiagram(document.getElementById('vlessFlowBar'), 'vectorless');
  vectFlow  = new FlowDiagram(document.getElementById('vectFlowBar'),  'vector');

  // Tree viz
  treeViz = new TreeViz(document.getElementById('treeSvg'));

  // Chunk viz
  chunkViz = new ChunkViz(
    document.getElementById('chunkGrid'),
    document.getElementById('dataJourney')
  );

  // LLM Inspectors
  vlessInspector = new LlmInspector({
    sysPromptEl:    document.getElementById('vlessSysPrompt'),
    contextEl:      document.getElementById('vlessContextText'),
    contextWrapper: document.getElementById('vlessContextWrapper'),
    tokenBadgeEl:   document.getElementById('vlessTokenBadge'),
    questionEl:     document.getElementById('vlessQuestion'),
    responseEl:     document.getElementById('vlessResponse'),
    scanLineEl:     document.getElementById('vlessScanLine'),
    color: 'teal',
  });
  vectInspector = new LlmInspector({
    sysPromptEl:    document.getElementById('vectSysPrompt'),
    contextEl:      document.getElementById('vectContextText'),
    contextWrapper: document.getElementById('vectContextWrapper'),
    tokenBadgeEl:   document.getElementById('vectTokenBadge'),
    questionEl:     document.getElementById('vectQuestion'),
    responseEl:     document.getElementById('vectResponse'),
    scanLineEl:     document.getElementById('vectScanLine'),
    color: 'blue',
  });

  // Check config status
  fetch('/api/config/status').then(r => r.json()).then(d => {
    if (d.configured) {
      state.configured = true;
      setConfigStatus(true, 'Configured — model: ' + d.model);
      document.getElementById('indexBtn').disabled = false;
    }
  }).catch(() => {});

  // Enter-key shortcuts
  document.getElementById('questionInput').addEventListener('keydown', e => {
    if (e.key === 'Enter' && !document.getElementById('queryBtn').disabled) runQuery();
  });
  document.getElementById('apiKeyInput').addEventListener('keydown', e => {
    if (e.key === 'Enter') saveConfig();
  });
}

// ── Config ────────────────────────────────────────────────────
async function saveConfig() {
  const apiKey = document.getElementById('apiKeyInput').value.trim();
  const model  = document.getElementById('modelSelect').value;
  if (!apiKey) { toast('Please enter your API key', 'error'); return; }

  const btn = document.getElementById('configBtn');
  btn.disabled = true; btn.textContent = 'Saving…';

  try {
    const res  = await fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ apiKey, model }),
    });
    const data = await res.json();
    if (res.ok) {
      state.configured = true;
      setConfigStatus(true, 'Configured — model: ' + data.model);
      document.getElementById('indexBtn').disabled = false;
      toast('API key saved', 'ok');
    } else {
      toast(data.error || 'Configuration failed', 'error');
    }
  } catch (e) {
    toast('Network error: ' + e.message, 'error');
  } finally {
    btn.disabled = false; btn.textContent = 'Save Config';
  }
}

function setConfigStatus(ok, msg) {
  document.getElementById('configStatus').className = 'status-dot' + (ok ? ' ok' : '');
  document.getElementById('configStatusText').textContent = msg;
}

// ── Indexing ──────────────────────────────────────────────────
function startIndexing() {
  if (state.indexing) return;
  const pdfPath = document.getElementById('pdfPathInput').value.trim();
  if (!pdfPath) { toast('Please enter a PDF file path', 'error'); return; }

  state.indexing = true;
  state.docId    = null;

  // Reset all visuals
  resetPanels();
  setSpinner('teal', true);
  setSpinner('blue', false);

  document.getElementById('indexBtn').disabled = true;
  document.getElementById('indexBtn').textContent = 'Indexing…';
  document.getElementById('resultsCard').classList.add('hidden');
  document.getElementById('queryBtn').disabled = true;
  document.getElementById('questionInput').disabled = true;
  document.getElementById('vlessEmptyViz').classList.add('hidden');
  document.getElementById('vectEmptyViz').classList.add('hidden');

  streamSSE('POST', '/api/compare/index?pdfPath=' + encodeURIComponent(pdfPath),
    {}, handleIndexEvent, onIndexDone, onIndexError);
}

function handleIndexEvent(evtName, data) {
  const pipeline = data.pipeline;
  const type     = data.type || '';

  if (evtName === 'error' || type === 'error') {
    toast((data.title || 'Error') + ': ' + (data.detail || ''), 'error'); return;
  }
  if (evtName === 'session') {
    state.docId = data.docId; return;
  }
  if (!pipeline) return;

  const isTeal = pipeline === 'vectorless';

  if (isTeal) {
    vlessFlow.update(type);
    treeViz.update(type, data);
    appendLog('vlessLog', 'teal', type, data);
  } else {
    vectFlow.update(type);
    chunkViz.update(type, data);
    appendLog('vectLog', 'blue', type, data);
  }
}

function onIndexDone() {
  state.indexing = false;
  setSpinner('teal', false);
  setSpinner('blue', false);
  document.getElementById('indexBtn').disabled  = false;
  document.getElementById('indexBtn').textContent = 'Index Document';
  if (state.docId) {
    document.getElementById('queryBtn').disabled     = false;
    document.getElementById('questionInput').disabled = false;
    toast('Indexing complete! You can now ask questions.', 'ok');
  }
}

function onIndexError(err) {
  state.indexing = false;
  setSpinner('teal', false);
  setSpinner('blue', false);
  document.getElementById('indexBtn').disabled  = false;
  document.getElementById('indexBtn').textContent = 'Index Document';
  toast('Indexing error: ' + (err || 'Unknown'), 'error');
}

// ── Query ──────────────────────────────────────────────────────
function runQuery() {
  if (state.querying || !state.docId) return;
  const question = document.getElementById('questionInput').value.trim();
  if (!question) { toast('Please enter a question', 'error'); return; }

  state.querying = true;
  setSpinner('teal', true);
  setSpinner('blue', true);

  // Add separator to logs
  addLogSeparator('vlessLog', 'teal', 'Query Phase');
  addLogSeparator('vectLog',  'blue', 'Query Phase');

  // Reset inspectors
  vlessInspector.reset();
  vectInspector.reset();

  // Show answers card (waiting state)
  document.getElementById('resultsCard').classList.remove('hidden');
  document.getElementById('vlessAnswer').innerHTML = '<span class="answer-placeholder">Generating…</span>';
  document.getElementById('vectAnswer').innerHTML  = '<span class="answer-placeholder">Generating…</span>';

  document.getElementById('queryBtn').disabled   = true;
  document.getElementById('queryBtn').textContent = 'Querying…';

  const url = '/api/compare/query?docId=' + encodeURIComponent(state.docId)
            + '&question=' + encodeURIComponent(question);
  streamSSE('GET', url, {}, handleQueryEvent, onQueryDone, onQueryError);
}

function handleQueryEvent(evtName, data) {
  if (evtName === 'comparison_complete') {
    document.getElementById('vlessAnswer').textContent = data.vectorlessAnswer || '(no answer)';
    document.getElementById('vectAnswer').textContent  = data.vectorAnswer     || '(no answer)';
    return;
  }

  const pipeline = data.pipeline;
  const type     = data.type || '';
  if (!pipeline) return;

  const isTeal = pipeline === 'vectorless';

  if (isTeal) {
    vlessFlow.update(type);
    treeViz.update(type, data);
    vlessInspector.update(type, data);
    appendLog('vlessLog', 'teal', type, data);
  } else {
    vectFlow.update(type);
    chunkViz.update(type, data);
    vectInspector.update(type, data);
    appendLog('vectLog', 'blue', type, data);
  }
}

function onQueryDone() {
  state.querying = false;
  setSpinner('teal', false);
  setSpinner('blue', false);
  document.getElementById('queryBtn').disabled   = false;
  document.getElementById('queryBtn').textContent = 'Run Query';
  toast('Query complete!', 'ok');
}

function onQueryError(err) {
  state.querying = false;
  setSpinner('teal', false);
  setSpinner('blue', false);
  document.getElementById('queryBtn').disabled   = false;
  document.getElementById('queryBtn').textContent = 'Run Query';
  toast('Query error: ' + (err || 'Unknown'), 'error');
}

// ── SSE Streaming ─────────────────────────────────────────────
async function streamSSE(method, url, body, onEvent, onDone, onErr) {
  try {
    const opts = { method, headers: { 'Accept': 'text/event-stream' } };
    if (method === 'POST' && body && Object.keys(body).length) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    const res = await fetch(url, opts);
    if (!res.ok) { onErr('HTTP ' + res.status); return; }

    const reader  = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      const lines = buffer.split('\n');
      buffer = lines.pop();

      let eventName = 'message', dataLines = [];
      for (const line of lines) {
        if (line.startsWith('event:'))      { eventName = line.slice(6).trim(); }
        else if (line.startsWith('data:'))  { dataLines.push(line.slice(5).trim()); }
        else if (line === '' && dataLines.length) {
          try { onEvent(eventName, JSON.parse(dataLines.join('\n'))); }
          catch { onEvent(eventName, { raw: dataLines.join('\n') }); }
          dataLines = []; eventName = 'message';
        }
      }
    }
    onDone();
  } catch (e) {
    onErr(e.message);
  }
}

// ── Log helpers ───────────────────────────────────────────────
const ICONS = {
  phase:'⚡', pages_parsed:'📄', toc_detected:'📑', tree_node:'🌿',
  verification:'✅', indexing_complete:'🎉', query_started:'💬',
  tree_overview:'🗺️', nav_level:'🧭', nav_llm_prompt:'🤖',
  node_selected:'🎯', pages_fetched:'📥', context_assembled:'📦',
  llm_prompt_sent:'📤', llm_response_received:'📥', answer:'✨',
  error:'❌', chunk:'📝', embedding:'🔢', similarity:'📐',
  similarity_scores:'🏆', chunks_selected:'📌', query_embedding:'🔢',
};

function appendLog(logId, color, type, data) {
  const list  = document.getElementById(logId);
  if (!list)  return;

  const item  = document.createElement('div');
  const isAnswer   = type === 'answer';
  const isComplete = type === 'indexing_complete';
  const isError    = type === 'error';
  item.className   = 'step-item ' + color
    + (isAnswer   ? ' answer'   : '')
    + (isComplete ? ' complete' : '')
    + (isError    ? ' error'    : '');

  const icon = ICONS[type] || '▸';
  item.innerHTML = `
    <span class="step-icon">${icon}</span>
    <div class="step-content">
      <div class="step-title">${esc(data.title || type)}</div>
      ${data.detail ? `<div class="step-detail">${esc(data.detail.substring(0, 200))}</div>` : ''}
    </div>`;
  list.appendChild(item);
  list.scrollTop = list.scrollHeight;
}

function addLogSeparator(logId, color, label) {
  const list = document.getElementById(logId);
  if (!list) return;
  const sep = document.createElement('div');
  sep.style.cssText = 'display:flex;align-items:center;gap:8px;margin:6px 0;';
  sep.innerHTML = `<div style="flex:1;height:1px;background:var(--border)"></div>
    <span style="font-size:9px;font-weight:700;text-transform:uppercase;letter-spacing:.8px;color:var(--${color});background:var(--${color}-dim);padding:2px 8px;border-radius:20px">${esc(label)}</span>
    <div style="flex:1;height:1px;background:var(--border)"></div>`;
  list.appendChild(sep);
}

// ── Panel/spinner helpers ─────────────────────────────────────
function resetPanels() {
  ['vlessLog','vectLog'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.innerHTML = '';
  });
  treeViz.reset();
  chunkViz.reset();
  vlessInspector.reset();
  vectInspector.reset();
}

function setSpinner(color, active) {
  const el = document.getElementById('spinner' + (color === 'teal' ? 'Teal' : 'Blue'));
  if (el) el.className = 'panel-spinner ' + color + (active ? ' active' : '');
}

// ── Toast ──────────────────────────────────────────────────────
let _toastTimer = null;
function toast(msg, type = 'info') {
  let el = document.getElementById('toast');
  if (!el) { el = document.createElement('div'); el.id = 'toast'; document.body.appendChild(el); }
  el.className = 'toast ' + type;
  el.textContent = msg;
  el.style.display = 'block';
  clearTimeout(_toastTimer);
  _toastTimer = setTimeout(() => { el.style.display = 'none'; }, 4000);
}

function esc(str) {
  if (str == null) return '';
  return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

// ── Bootstrap ─────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', init);
