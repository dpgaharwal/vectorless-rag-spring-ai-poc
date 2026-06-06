'use strict';
/* ═══════════════════════════════════════════════════════════════
   ChunkViz — chunk card grid + similarity bars + data-packet trail
   ═══════════════════════════════════════════════════════════════ */

const VEC_COLORS = [
  '#00d4a0','#4d9fff','#ffc857','#ff6b6b','#5cffa0','#a78bfa','#fb923c','#38bdf8'
];

class ChunkViz {
  constructor(gridEl, journeyEl) {
    this.grid    = gridEl;
    this.journey = journeyEl;
    this.cards   = {};      // chunkIndex → card DOM element
    this.chunks  = [];
    this._overflow = null;
  }

  reset() {
    this.grid.innerHTML    = '';
    this.journey.innerHTML = '';
    this.cards  = {};
    this.chunks = [];
    this._overflow = null;
  }

  update(type, data) {
    switch (type) {
      case 'chunk':
        this._addChunk(data.data);
        break;
      case 'chunk_overflow':
        this._showOverflow(data.data);
        break;
      case 'embedding':
        this._addPacket('🔢', 'Embedding chunk ' + ((data.data?.chunkIndex ?? 0) + 1), 'dp-blue');
        break;
      case 'indexing_complete':
        this._addPacket('🗃️', 'Stored in SimpleVectorStore', 'dp-teal');
        break;
      case 'query_embedding':
        this._addPacket('🔍', 'Query embedded → 1536-dim vector', 'dp-blue');
        break;
      case 'similarity':
        this._addPacket('📐', 'Computing cosine similarity…', 'dp-blue');
        break;
      case 'similarity_scores':
        this._showScores(data.data);
        this._addPacket('🏆', 'Top-K chunks identified', 'dp-teal');
        break;
      case 'context_assembled':
        this._addPacket('📦', 'Context assembled → sending to LLM', 'dp-teal');
        break;
    }
  }

  // ── Chunk Cards ──────────────────────────────────────────────

  _addChunk(d) {
    if (!d) return;
    const idx   = d.chunkIndex ?? this.chunks.length;
    const total = d.totalChunks ?? '?';
    const preview = d.preview || '';

    this.chunks.push({ idx, preview });

    const card = document.createElement('div');
    card.className = 'chunk-card';
    card.style.animationDelay = Math.min(idx * 60, 800) + 'ms';
    card.dataset.chunkIdx = idx;

    card.innerHTML = `
      <div class="top-k-badge">TOP</div>
      <div class="chunk-num">Chunk #${idx + 1}</div>
      <div class="chunk-text">${esc(preview)}</div>
      <div class="vec-bars">${this._vecBars(idx)}</div>
      <div class="score-bar-row" id="score-row-${idx}">
        <div class="score-bar-track"><div class="score-bar-fill" id="score-fill-${idx}"></div></div>
        <div class="score-val" id="score-val-${idx}"></div>
      </div>
    `;

    this.cards[idx] = card;
    this.grid.appendChild(card);
  }

  _showOverflow(d) {
    if (!d) return;
    const note = document.createElement('div');
    note.className = 'chunk-card';
    note.style.opacity = '.5';
    note.style.display = 'flex';
    note.style.alignItems = 'center';
    note.style.justifyContent = 'center';
    note.innerHTML = `<div style="font-size:11px;color:var(--text-dim);text-align:center">+${d.hiddenCount} more<br>chunks</div>`;
    this.grid.appendChild(note);
  }

  _vecBars(seed) {
    return VEC_COLORS.map((color, i) => {
      const h = 4 + Math.abs(Math.sin(seed * 7 + i * 3)) * 12;
      return `<div class="vec-bar" style="height:${h.toFixed(1)}px;background:${color}"></div>`;
    }).join('');
  }

  // ── Similarity Scores ────────────────────────────────────────

  _showScores(d) {
    if (!d) return;
    const { scores = [], topKIndices = [] } = d;
    const topKSet = new Set(topKIndices);

    // Dim all cards
    Object.values(this.cards).forEach(card => card.classList.add('dim'));

    // Show score bars for all scored chunks
    scores.forEach(s => {
      const idx   = s.chunkIndex;
      const score = s.score ?? 0;
      const row   = document.getElementById('score-row-' + idx);
      const fill  = document.getElementById('score-fill-' + idx);
      const val   = document.getElementById('score-val-' + idx);

      if (!row) return;
      row.classList.add('visible');

      // Animate bar after a tiny delay
      setTimeout(() => {
        if (fill) fill.style.width = Math.round(score * 100) + '%';
        if (val)  val.textContent  = score.toFixed(2);
      }, 200);
    });

    // After bars animate, highlight top-K
    setTimeout(() => {
      topKSet.forEach(idx => {
        const card = this.cards[idx];
        if (!card) return;
        card.classList.remove('dim');
        card.classList.add('top-k');
        card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      });
    }, 900);
  }

  // ── Data Packet Trail ────────────────────────────────────────

  _addPacket(icon, label, colorClass) {
    const packet = document.createElement('div');
    packet.className = `data-packet ${colorClass}`;
    packet.style.animationDelay = '0ms';
    packet.innerHTML = `<span class="dp-icon">${icon}</span>${esc(label)}`;
    this.journey.appendChild(packet);
    this.journey.scrollTop = this.journey.scrollHeight;
  }
}

function esc(str) {
  if (str == null) return '';
  return String(str)
    .replace(/&/g,'&amp;').replace(/</g,'&lt;')
    .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
