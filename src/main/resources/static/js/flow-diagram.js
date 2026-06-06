'use strict';
/* ═══════════════════════════════════════════════════════════════
   FlowDiagram — animated horizontal pipeline step bar
   ═══════════════════════════════════════════════════════════════ */

const FLOW_STEPS = {
  vectorless: {
    indexing: [
      { id: 'pdf',       icon: '📄', label: 'PDF Input'   },
      { id: 'parse',     icon: '🔍', label: 'Parse Pages' },
      { id: 'toc',       icon: '📑', label: 'TOC Detect'  },
      { id: 'tree',      icon: '🌿', label: 'Build Tree'  },
      { id: 'verify',    icon: '✅', label: 'Verify'      },
      { id: 'summaries', icon: '💬', label: 'Summaries'   },
      { id: 'done',      icon: '🎉', label: 'Complete'    },
    ],
    query: [
      { id: 'question',  icon: '❓', label: 'Question'    },
      { id: 'treeview',  icon: '🗺️', label: 'Tree View'  },
      { id: 'navigate',  icon: '🧭', label: 'Navigate'    },
      { id: 'select',    icon: '🎯', label: 'Select Node' },
      { id: 'fetch',     icon: '📥', label: 'Fetch Pages' },
      { id: 'assemble',  icon: '📦', label: 'Assemble'    },
      { id: 'llmcall',   icon: '🤖', label: 'LLM Call'    },
      { id: 'answer',    icon: '✨', label: 'Answer'      },
    ],
  },
  vector: {
    indexing: [
      { id: 'pdf',       icon: '📄', label: 'PDF Input'     },
      { id: 'read',      icon: '📖', label: 'Read Pages'    },
      { id: 'chunk',     icon: '✂️', label: 'Split Chunks'  },
      { id: 'embed',     icon: '🔢', label: 'Embed'         },
      { id: 'store',     icon: '🗃️', label: 'Store Vectors' },
      { id: 'done',      icon: '🎉', label: 'Complete'      },
    ],
    query: [
      { id: 'question',  icon: '❓', label: 'Question'        },
      { id: 'embed_q',   icon: '🔢', label: 'Embed Query'     },
      { id: 'search',    icon: '📐', label: 'Cosine Search'   },
      { id: 'score',     icon: '🏆', label: 'Score & Rank'    },
      { id: 'topk',      icon: '📌', label: 'Top-K Select'    },
      { id: 'assemble',  icon: '📦', label: 'Assemble'        },
      { id: 'llmcall',   icon: '🤖', label: 'LLM Call'        },
      { id: 'answer',    icon: '✨', label: 'Answer'          },
    ],
  },
};

// Map SSE event type → step id  (phase = 'indexing' | 'query')
const EVENT_TO_STEP = {
  vectorless: {
    indexing: {
      'pages_parsed':      'parse',
      'toc_detected':      'toc',
      'tree_node':         'tree',
      'verification':      'verify',
      'indexing_complete': 'done',
    },
    query: {
      'query_started':         'question',
      'tree_overview':         'treeview',
      'nav_level':             'navigate',
      'node_selected':         'select',
      'pages_fetched':         'fetch',
      'context_assembled':     'assemble',
      'llm_prompt_sent':       'llmcall',
      'llm_response_received': 'answer',
    },
  },
  vector: {
    indexing: {
      'chunk':             'chunk',
      'embedding':         'embed',
      'indexing_complete': 'done',
    },
    query: {
      'query_started':         'question',
      'query_embedding':       'embed_q',
      'similarity':            'search',
      'similarity_scores':     'score',
      'chunks_selected':       'topk',
      'context_assembled':     'assemble',
      'llm_prompt_sent':       'llmcall',
      'llm_response_received': 'answer',
    },
  },
};

// Also trigger 'parse' / 'read' from first 'phase' event per pipeline
const FIRST_PHASE_STEP = { vectorless: 'parse', vector: 'read' };

class FlowDiagram {
  constructor(container, pipeline) {
    this.container = container;
    this.pipeline  = pipeline;           // 'vectorless' | 'vector'
    this.color     = pipeline === 'vectorless' ? 'teal' : 'blue';
    this.phase     = 'indexing';         // 'indexing' | 'query'
    this.activeId  = null;
    this.doneIds   = new Set();
    this.firstPhase = true;
    this._render();
    this._activate('pdf');
  }

  // Called with every SSE event
  update(type) {
    // Switch phase on first query event
    if (type === 'query_started' && this.phase === 'indexing') {
      this.phase   = 'query';
      this.activeId = null;
      this.doneIds  = new Set();
      this.firstPhase = true;
      this._render();
    }

    // First 'phase' event in current phase activates first step
    if (type === 'phase' && this.firstPhase) {
      this.firstPhase = false;
      const firstStep = this.phase === 'indexing' ? FIRST_PHASE_STEP[this.pipeline] : null;
      if (firstStep) this._activate(firstStep);
      return;
    }

    const mapping = EVENT_TO_STEP[this.pipeline]?.[this.phase];
    if (!mapping) return;
    const stepId = mapping[type];
    if (stepId) this._activate(stepId);
  }

  _activate(stepId) {
    if (this.activeId && this.activeId !== stepId) {
      this.doneIds.add(this.activeId);
    }
    this.activeId = stepId;
    this._updateDOM();
  }

  _render() {
    const steps = FLOW_STEPS[this.pipeline][this.phase];
    this.container.innerHTML = '';

    const row = document.createElement('div');
    row.className = 'flow-steps';

    steps.forEach((step, i) => {
      // Step node
      const stepEl = document.createElement('div');
      stepEl.className = 'flow-step';
      stepEl.dataset.stepId = step.id;

      const node = document.createElement('div');
      node.className = 'flow-node';
      node.textContent = step.icon;

      const label = document.createElement('div');
      label.className = 'flow-label';
      label.textContent = step.label;

      stepEl.appendChild(node);
      stepEl.appendChild(label);
      row.appendChild(stepEl);

      // Connector + particle (between steps)
      if (i < steps.length - 1) {
        const conn = document.createElement('div');
        conn.className = 'flow-connector';
        conn.dataset.afterStep = step.id;

        const particle = document.createElement('div');
        particle.className = `flow-particle ${this.color}`;
        conn.appendChild(particle);
        row.appendChild(conn);
      }
    });

    this.container.appendChild(row);
    this._updateDOM();
  }

  _updateDOM() {
    const steps = this.container.querySelectorAll('.flow-step');
    const connectors = this.container.querySelectorAll('.flow-connector');

    steps.forEach(el => {
      const id = el.dataset.stepId;
      const node = el.querySelector('.flow-node');
      el.classList.remove('active', 'done');
      node.classList.remove('active', this.color, 'done');

      if (this.doneIds.has(id)) {
        el.classList.add('done');
        node.classList.add('done');
        node.textContent = '✓';
      } else if (id === this.activeId) {
        el.classList.add('active');
        node.classList.add('active', this.color);
      }
    });

    connectors.forEach(conn => {
      const afterStep = conn.dataset.afterStep;
      const particle  = conn.querySelector('.flow-particle');
      conn.classList.remove('active', 'done', 'teal', 'blue');
      particle.classList.remove('running');

      if (this.doneIds.has(afterStep)) {
        conn.classList.add('done', this.color);
      } else if (afterStep === this.activeId) {
        conn.classList.add('active');
        particle.classList.add('running');
      }
    });
  }
}
