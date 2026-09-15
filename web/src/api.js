// Everything the console needs. Vite proxies /api to the platform and /agent
// to the python service, so the browser only ever talks to one origin.

async function get(url) {
  const r = await fetch(url)
  if (!r.ok) throw new Error(`${url} -> ${r.status}`)
  return r.json()
}

async function post(url, body) {
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!r.ok) throw new Error(`${url} -> ${r.status} ${await r.text()}`)
  return r.json()
}

// tool responses are {ok, data, error}. unwrap so callers deal in facts.
const unwrap = (res) => {
  if (res && res.ok === false) throw new Error(res.error + ': ' + (res.detail || ''))
  return res && 'data' in res ? res.data : res
}

export const api = {
  product: (sku) => get(`/api/tools/product?sku=${sku}`).then(unwrap),

  inventory: (sku, nodeId) =>
    get(`/api/tools/inventory-position?sku=${sku}&nodeId=${nodeId}`).then(unwrap),

  forecast: (sku, nodeId) =>
    get(`/api/tools/demand-forecast?sku=${sku}&nodeId=${nodeId}&horizonDays=14`).then(unwrap),

  openPos: (sku, nodeId) =>
    get(`/api/tools/open-pos?sku=${sku}&nodeId=${nodeId}`).then(unwrap),

  suppliers: (sku) => get(`/api/tools/suppliers?sku=${sku}`).then(unwrap),

  budget: (nodeId, category) =>
    get(`/api/tools/budget?nodeId=${nodeId}&category=${category}`).then(unwrap),

  anomaly: (sku, nodeId) =>
    get(`/api/tools/demand-anomaly?sku=${sku}&nodeId=${nodeId}`).then(unwrap),

  plan: (sku, nodeId, supplierId) =>
    post('/api/tools/calculate-reorder', { sku, nodeId, supplierId }).then(unwrap),

  startRun: (body) => post('/api/runs', body),
  run: (id) => get(`/api/runs/${id}`),
  runs: () => get('/api/runs'),

  decide: (body) => post('/agent/decide', body),

  approvals: () => get('/api/approvals'),
  decideApproval: (id, decision, note) =>
    post(`/api/approvals/${id}/decide`, { decision, note, decidedBy: 'buyer:ana' }),
}

// the seeded scenarios, so the console is clickable without knowing the data
export const SCENARIOS = [
  {
    id: 'S1_REVIEW', sku: 'SKU-MILK-1L', node: 'NODE-BOG-01',
    supplier: 'SUP-LACTEO', category: 'dairy', recommended: 800,
    label: 'Recommendation is 4x too high',
    note: 'An open PO already covers most of the demand. Accepting 800 is wrong, and so is rejecting outright.',
  },
  {
    id: 'S1_REVIEW', sku: 'SKU-MILK-1L', node: 'NODE-BOG-01',
    supplier: 'SUP-LACTEO', category: 'dairy', recommended: 204,
    label: 'Recommendation is already right',
    note: 'The agent has to be able to agree. One that always finds fault is as useless as one that never does.',
  },
  {
    id: 'S3_DEMAND', sku: 'SKU-CHIPS-150G', node: 'NODE-BOG-01',
    supplier: 'SUP-SNACKCO', category: 'snacks', recommended: null,
    label: 'Demand really did move',
    note: '14 straight days at 2.3x forecast with no promotion behind it.',
  },
  {
    id: 'S3_DEMAND', sku: 'SKU-SODA-2L', node: 'NODE-BOG-01',
    supplier: 'SUP-BEBIDAS', category: 'beverages', recommended: null,
    label: 'Demand only looks like it moved',
    note: 'Identical shape on a chart. It is a promotion plus one 1070 unit b2b order.',
  },
  {
    id: 'S1_REVIEW', sku: 'SKU-RICE-5KG', node: 'NODE-BOG-01',
    supplier: 'SUP-ARROZMX', category: 'grocery', recommended: 1200,
    label: 'No legal order exists',
    note: 'Needs 713, affords 140, supplier minimum is 1000. The answer is a brief for a human.',
  },
]
