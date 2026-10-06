import React, { useEffect, useState } from 'react'
import { api } from './api.js'

// what each kind of exception asks the agent to look at
const SCENARIO = { DEMAND_SHIFT: 'S3_DEMAND' }

/**
 * The nightly batch. The deterministic planner looks at every sku at every
 * store with no model involved; only the rows it flags get an agent run, and
 * only when someone sends them.
 */
export default function Batch({ onOpen }) {
  const [rows, setRows] = useState([])
  const [summary, setSummary] = useState(null)
  const [busy, setBusy] = useState(false)
  const [sent, setSent] = useState({})

  useEffect(() => { api.exceptions().then(setRows).catch(() => setRows([])) }, [])

  async function run() {
    setBusy(true)
    try {
      const s = await api.runBatch()
      setSummary(s)
      setRows(s.exceptions)
    } finally {
      setBusy(false)
    }
  }

  async function send(e) {
    const key = e.id
    setSent({ ...sent, [key]: 'running…' })
    try {
      const scenario = SCENARIO[e.kind] || 'S1_REVIEW'
      const { runId } = await api.startRun({ scenario, sku: e.sku, nodeId: e.nodeId, supplierId: e.supplierId })
      const out = await api.decide({ run_id: runId, scenario, sku: e.sku, node_id: e.nodeId,
                                     supplier_id: e.supplierId, recommended_qty: null })
      setSent(s => ({ ...s, [key]: { runId, decision: out.decision, qty: out.qty,
                                     outcome: out.execution?.outcome } }))
    } catch (err) {
      setSent(s => ({ ...s, [key]: String(err.message || err) }))
    }
  }

  return (
    <div className="panel">
      <h2>Nightly batch</h2>
      <p className="muted">
        Every sku at every store goes through the planner, a two week shelf simulation and
        the demand check. No model runs here. The agent only looks at what you send it.
      </p>
      <button className="primary" onClick={run} disabled={busy}>
        {busy ? 'planning every sku…' : 'Run the batch now'}
      </button>
      {summary && (
        <p className="muted small">
          {summary.scanned} sku × store rows, {summary.exceptions.length} exceptions, {summary.durationMs} ms, 0 model calls
        </p>
      )}

      {rows.length === 0 && !busy && <p className="muted">No batch has run yet.</p>}
      {rows.length > 0 && (
        <table className="checks">
          <tbody>
            {rows.map(e => {
              const s = sent[e.id]
              return (
                <tr key={e.id}>
                  <td>{e.nodeId}</td>
                  <td>{e.sku}</td>
                  <td><span className={'tag ' + (e.kind === 'NEEDS_ORDER' ? 'ok' : 'warn')}>{e.kind}</span></td>
                  <td className="muted">{e.detail}</td>
                  <td>
                    {!s && <button className="link" onClick={() => send(e)}>send to agent</button>}
                    {typeof s === 'string' && <span className="muted">{s}</span>}
                    {s?.runId && (
                      <button className="link" onClick={() => onOpen(s.runId)}>
                        {s.decision} {s.qty > 0 ? s.qty : ''} · {(s.outcome || '').toLowerCase()} →
                      </button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </div>
  )
}
