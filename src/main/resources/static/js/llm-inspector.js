'use strict';
/* ═══════════════════════════════════════════════════════════════
   LlmInspector — "What we sent / what we got" panel
   ═══════════════════════════════════════════════════════════════ */

class LlmInspector {
  constructor(opts) {
    // opts: { sysPromptEl, contextEl, contextWrapper, tokenBadgeEl, questionEl, responseEl, scanLineEl, color }
    this.sysPromptEl    = opts.sysPromptEl;
    this.contextEl      = opts.contextEl;
    this.contextWrapper = opts.contextWrapper;
    this.tokenBadgeEl   = opts.tokenBadgeEl;
    this.questionEl     = opts.questionEl;
    this.responseEl     = opts.responseEl;
    this.scanLineEl     = opts.scanLineEl;
    this.color          = opts.color || 'teal';    // 'teal' | 'blue'
    this._twTimer       = null;
    this._cursor        = null;
  }

  update(type, data) {
    const d = data.data;
    switch (type) {
      case 'context_assembled':
        if (d?.contextText) this._setContext(d.contextText, d.charCount, d.tokenEstimate);
        break;
      case 'llm_prompt_sent':
        if (d) this._setPrompt(d.systemPrompt, d.question);
        break;
      case 'llm_response_received':
        if (d?.responseText) this._typewrite(d.responseText);
        break;
    }
  }

  reset() {
    if (this.sysPromptEl)  this.sysPromptEl.textContent  = '—';
    if (this.contextEl)    this.contextEl.textContent    = '—';
    if (this.tokenBadgeEl) this.tokenBadgeEl.textContent = '';
    if (this.questionEl)   this.questionEl.textContent   = '—';
    if (this.responseEl)   this.responseEl.innerHTML     = '<span class="response-placeholder">Waiting for LLM response…</span>';
    if (this._twTimer) { clearInterval(this._twTimer); this._twTimer = null; }
  }

  // ── Context box ──────────────────────────────────────────────

  _setContext(text, charCount, tokenEstimate) {
    if (!this.contextEl) return;
    this.contextEl.textContent = text;

    if (this.tokenBadgeEl) {
      const tokens = tokenEstimate ?? Math.round(charCount / 4);
      this.tokenBadgeEl.textContent = `${charCount?.toLocaleString() ?? '?'} chars · ~${tokens?.toLocaleString() ?? '?'} tokens`;
    }

    // Trigger scan-line animation
    if (this.scanLineEl) {
      this.scanLineEl.classList.remove('hidden');
      this.scanLineEl.style.animation = 'none';
      void this.scanLineEl.offsetWidth; // reflow
      this.scanLineEl.style.animation = '';
      const isBlue = this.color === 'blue';
      this.scanLineEl.className = 'scan-line' + (isBlue ? ' blue-scan' : '');
      setTimeout(() => this.scanLineEl.classList.add('hidden'), 1600);
    }
  }

  // ── Prompt boxes ─────────────────────────────────────────────

  _setPrompt(systemPrompt, question) {
    if (this.sysPromptEl && systemPrompt) {
      this.sysPromptEl.textContent = systemPrompt;
    }
    if (this.questionEl && question) {
      this.questionEl.textContent = question;
    }
  }

  // ── Typewriter response ──────────────────────────────────────

  _typewrite(text) {
    if (!this.responseEl) return;

    // Clear any previous
    if (this._twTimer) { clearInterval(this._twTimer); this._twTimer = null; }

    this.responseEl.innerHTML = '';

    const textNode = document.createTextNode('');
    this.responseEl.appendChild(textNode);

    const cursor = document.createElement('span');
    cursor.className = `typewriter-cursor${this.color === 'blue' ? ' blue' : ''}`;
    this.responseEl.appendChild(cursor);
    this._cursor = cursor;

    let i = 0;
    const speed = Math.max(10, Math.min(40, Math.round(20000 / text.length))); // adaptive speed
    this._twTimer = setInterval(() => {
      const chunk = text.slice(i, i + 3); // write 3 chars per tick for longer texts
      textNode.textContent += chunk;
      i += 3;
      this.responseEl.scrollTop = this.responseEl.scrollHeight;
      if (i >= text.length) {
        textNode.textContent = text; // ensure complete
        clearInterval(this._twTimer);
        this._twTimer = null;
        // Remove blinking cursor after done
        setTimeout(() => cursor.remove(), 2000);
      }
    }, speed);
  }
}
