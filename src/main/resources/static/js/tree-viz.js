'use strict';
/* ═══════════════════════════════════════════════════════════════
   TreeViz — SVG hierarchical tree with animated traversal
   ═══════════════════════════════════════════════════════════════ */

const SVG_NS = 'http://www.w3.org/2000/svg';

const T = {
  nodeW: 160, nodeH: 50,
  hGap:  24,            // horizontal gap between siblings
  vGap:  70,            // vertical gap between levels
  padX:  20, padY: 20,
};

class TreeViz {
  constructor(svgEl) {
    this.svg      = svgEl;
    this.positions = {};   // nodeId → { x, y, node }
    this.drawn    = false;
    this._addDefs();
  }

  // ── Public API ─────────────────────────────────────────────

  update(type, data) {
    switch (type) {
      case 'tree_overview':
        if (data.data?.nodes) this.drawTree(data.data.nodes);
        break;
      case 'indexing_complete':
        if (data.data?.nodes) this.drawTree(data.data.nodes);
        break;
      case 'nav_level':
        if (data.data?.candidates) this._highlightCandidates(data.data.candidates.map(c => c.nodeId));
        break;
      case 'node_selected':
        if (data.data?.nodeId != null) this._selectNode(data.data.nodeId, data.data.depth);
        break;
      case 'pages_fetched':
        if (data.data?.nodeId != null) this._pulseLeaf(data.data.nodeId);
        break;
    }
  }

  drawTree(rootNodes) {
    this.svg.innerHTML = '';
    this._addDefs();
    this.positions = {};

    // Layout
    const allPos = this._layout(rootNodes, T.padX, T.padY);
    if (allPos.length === 0) return;

    // Calculate SVG size
    let maxX = 0, maxY = 0;
    allPos.forEach(p => {
      maxX = Math.max(maxX, p.x + T.nodeW);
      maxY = Math.max(maxY, p.y + T.nodeH);
    });
    this.svg.setAttribute('width',  maxX + T.padX);
    this.svg.setAttribute('height', maxY + T.padY);
    this.svg.style.minWidth = (maxX + T.padX) + 'px';

    // Draw edges first (behind nodes)
    allPos.forEach(p => {
      if (p.parentId != null) {
        const par = this.positions[p.parentId];
        if (par) this._drawEdge(par, p);
      }
    });

    // Draw nodes
    allPos.forEach(p => {
      this._drawNode(p);
      this.positions[p.nodeId] = p;
    });

    this.drawn = true;
  }

  reset() {
    this.svg.innerHTML = '';
    this._addDefs();
    this.positions = {};
    this.drawn = false;
  }

  // ── Layout (recursive, subtree-width based) ──────────────────

  _layout(nodes, startX, startY, parentId = null, result = []) {
    if (!nodes || nodes.length === 0) return result;

    // Limit to first 8 nodes per level for readability
    const visible = nodes.slice(0, 8);

    visible.forEach(node => {
      const id = node.nodeId || node.title || Math.random().toString(36).slice(2);
      const leaves = this._countLeaves(node);
      const subtreeW = leaves * (T.nodeW + T.hGap) - T.hGap;

      const cx = startX + subtreeW / 2 - T.nodeW / 2;

      result.push({
        nodeId:   id,
        parentId: parentId,
        x: cx, y: startY,
        node: node,
      });

      if (node.nodes && node.nodes.length > 0) {
        this._layout(node.nodes, startX, startY + T.nodeH + T.vGap, id, result);
      }

      startX += subtreeW + T.hGap;
    });

    return result;
  }

  _countLeaves(node) {
    if (!node.nodes || node.nodes.length === 0) return 1;
    return node.nodes.reduce((s, c) => s + this._countLeaves(c), 0);
  }

  // ── SVG Drawing ──────────────────────────────────────────────

  _drawNode(p) {
    const g = document.createElementNS(SVG_NS, 'g');
    g.setAttribute('data-node-id', p.nodeId);
    g.style.cursor = 'default';

    // Background rect
    const rect = document.createElementNS(SVG_NS, 'rect');
    rect.setAttribute('x', p.x);
    rect.setAttribute('y', p.y);
    rect.setAttribute('width',  T.nodeW);
    rect.setAttribute('height', T.nodeH);
    rect.setAttribute('rx', '8');
    rect.setAttribute('fill',   '#1e1e32');
    rect.setAttribute('stroke', '#353558');
    rect.setAttribute('stroke-width', '1.5');
    rect.setAttribute('class', 'tree-node-rect');
    g.appendChild(rect);

    // Title text
    const title = (p.node.title || 'Section').substring(0, 22);
    const tspan1 = document.createElementNS(SVG_NS, 'text');
    tspan1.setAttribute('x', p.x + T.nodeW / 2);
    tspan1.setAttribute('y', p.y + 18);
    tspan1.setAttribute('text-anchor', 'middle');
    tspan1.setAttribute('font-size', '10');
    tspan1.setAttribute('font-weight', '600');
    tspan1.setAttribute('fill', '#c4c4d8');
    tspan1.setAttribute('font-family', 'system-ui,sans-serif');
    tspan1.textContent = title;
    g.appendChild(tspan1);

    // Summary (truncated)
    if (p.node.summary) {
      const sumTxt = p.node.summary.substring(0, 30) + (p.node.summary.length > 30 ? '…' : '');
      const tspan2 = document.createElementNS(SVG_NS, 'text');
      tspan2.setAttribute('x', p.x + T.nodeW / 2);
      tspan2.setAttribute('y', p.y + 30);
      tspan2.setAttribute('text-anchor', 'middle');
      tspan2.setAttribute('font-size', '8');
      tspan2.setAttribute('fill', '#55556a');
      tspan2.setAttribute('font-family', 'system-ui,sans-serif');
      tspan2.textContent = sumTxt;
      g.appendChild(tspan2);
    }

    // Pages badge
    const pageTxt = `pp.${p.node.startIndex}–${p.node.endIndex}`;
    const pagesG = document.createElementNS(SVG_NS, 'g');
    const pBg = document.createElementNS(SVG_NS, 'rect');
    pBg.setAttribute('x', p.x + T.nodeW / 2 - 22);
    pBg.setAttribute('y', p.y + T.nodeH - 14);
    pBg.setAttribute('width', '44');
    pBg.setAttribute('height', '10');
    pBg.setAttribute('rx', '3');
    pBg.setAttribute('fill', '#26263e');
    pagesG.appendChild(pBg);

    const pTxt = document.createElementNS(SVG_NS, 'text');
    pTxt.setAttribute('x', p.x + T.nodeW / 2);
    pTxt.setAttribute('y', p.y + T.nodeH - 6);
    pTxt.setAttribute('text-anchor', 'middle');
    pTxt.setAttribute('font-size', '7');
    pTxt.setAttribute('fill', '#55556a');
    pTxt.setAttribute('font-family', 'system-ui,sans-serif');
    pTxt.textContent = pageTxt;
    pagesG.appendChild(pTxt);
    g.appendChild(pagesG);

    this.svg.appendChild(g);

    // Store rect reference for highlight
    p.rect = rect;
  }

  _drawEdge(from, to) {
    const x1 = from.x + T.nodeW / 2;
    const y1 = from.y + T.nodeH;
    const x2 = to.x   + T.nodeW / 2;
    const y2 = to.y;
    const cy = (y1 + y2) / 2;

    const path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('d', `M ${x1} ${y1} C ${x1} ${cy} ${x2} ${cy} ${x2} ${y2}`);
    path.setAttribute('fill', 'none');
    path.setAttribute('stroke', '#252540');
    path.setAttribute('stroke-width', '1.5');
    path.setAttribute('class', 'tree-edge');
    path.setAttribute('data-from', from.nodeId);
    path.setAttribute('data-to',   to.nodeId);
    this.svg.insertBefore(path, this.svg.firstChild);
    from.edgeTo = from.edgeTo || {};
    from.edgeTo[to.nodeId] = path;
  }

  // ── Highlighting ─────────────────────────────────────────────

  _highlightCandidates(nodeIds) {
    // Remove any old amber rings
    this.svg.querySelectorAll('.candidate-ring').forEach(el => el.remove());

    nodeIds.forEach(id => {
      const p = this.positions[id];
      if (!p) return;

      const ring = document.createElementNS(SVG_NS, 'rect');
      ring.setAttribute('x', p.x - 3);
      ring.setAttribute('y', p.y - 3);
      ring.setAttribute('width',  T.nodeW + 6);
      ring.setAttribute('height', T.nodeH + 6);
      ring.setAttribute('rx', '10');
      ring.setAttribute('fill', 'none');
      ring.setAttribute('stroke', '#ffc857');
      ring.setAttribute('stroke-width', '2');
      ring.setAttribute('class', 'candidate-ring');
      ring.style.animation = 'candidate-pulse 1s ease-in-out infinite';
      this.svg.appendChild(ring);
    });
  }

  _selectNode(nodeId, depth) {
    // Remove candidate rings
    this.svg.querySelectorAll('.candidate-ring').forEach(el => el.remove());

    const p = this.positions[nodeId];
    if (!p || !p.rect) return;

    // Highlight the selected rect
    p.rect.setAttribute('fill', 'rgba(0,212,160,0.15)');
    p.rect.setAttribute('stroke', '#00d4a0');
    p.rect.setAttribute('stroke-width', '2.5');

    // Find parent edge and animate traversal cursor
    const edgePath = this.svg.querySelector(`[data-to="${nodeId}"]`);
    if (edgePath) {
      edgePath.setAttribute('stroke', '#00d4a0');
      edgePath.setAttribute('stroke-width', '2');
      this._animateCursorAlongPath(edgePath);
    }
  }

  _pulseLeaf(nodeId) {
    const p = this.positions[nodeId];
    if (!p || !p.rect) return;
    p.rect.setAttribute('stroke', '#5cffa0');
    p.rect.setAttribute('stroke-width', '2.5');
    p.rect.setAttribute('fill', 'rgba(92,255,160,0.1)');
  }

  _animateCursorAlongPath(pathEl) {
    const cursor = document.createElementNS(SVG_NS, 'circle');
    cursor.setAttribute('r', '7');
    cursor.setAttribute('fill', '#00d4a0');
    cursor.setAttribute('filter', 'url(#tealGlow)');

    const motion = document.createElementNS(SVG_NS, 'animateMotion');
    motion.setAttribute('dur', '0.9s');
    motion.setAttribute('fill', 'freeze');

    const mpath = document.createElementNS(SVG_NS, 'mpath');
    mpath.setAttributeNS('http://www.w3.org/1999/xlink', 'xlink:href', '#' + this._getOrSetPathId(pathEl));
    motion.appendChild(mpath);
    cursor.appendChild(motion);

    this.svg.appendChild(cursor);
    motion.beginElement();
    setTimeout(() => cursor.remove(), 1200);
  }

  _getOrSetPathId(pathEl) {
    if (!pathEl.id) {
      pathEl.id = 'tp-' + Math.random().toString(36).slice(2);
    }
    return pathEl.id;
  }

  // ── Defs (filters) ───────────────────────────────────────────

  _addDefs() {
    const defs = document.createElementNS(SVG_NS, 'defs');

    // Teal glow filter
    const filter = document.createElementNS(SVG_NS, 'filter');
    filter.setAttribute('id', 'tealGlow');
    filter.setAttribute('x', '-50%'); filter.setAttribute('y', '-50%');
    filter.setAttribute('width', '200%'); filter.setAttribute('height', '200%');
    const fe = document.createElementNS(SVG_NS, 'feDropShadow');
    fe.setAttribute('dx', '0'); fe.setAttribute('dy', '0');
    fe.setAttribute('stdDeviation', '3');
    fe.setAttribute('flood-color', '#00d4a0');
    filter.appendChild(fe);
    defs.appendChild(filter);

    // Candidate pulse animation via CSS injection
    const style = document.createElementNS(SVG_NS, 'style');
    style.textContent = `
      @keyframes candidate-pulse {
        0%,100% { opacity: 1; stroke-width: 2; }
        50%      { opacity: .5; stroke-width: 3; }
      }
    `;
    defs.appendChild(style);
    this.svg.appendChild(defs);
  }
}
