import React, { useEffect, useState } from 'react'
import { api } from './api.js'

/**
 * The queue of decisions the agent reached but is not allowed to act on.
 *
 * A rejection needs a note, because that note is the only feedback the agent
 * ever gets about a decision a human disagreed with.
 */
export default function Approvals({ onOpen }) {
  const [items, setItems] = useState([])
  const [notes, setNotes] = useState({})
  const [busy, setBusy] = useState(null)
  const [outcome, setOutcome] = useState(null)

  const load = () => api.approvals().then(setItems).catch(() => setItems([]))
  useEffect(() => { load(); const t = setInterval(load, 5000); return () => clearInterval(t) }, [])

  async function decide(id, decision) {
    const note = notes[id] || ''
    if (decision === 'reject' && !note.trim()) {
      setOutcome({ id, error: 'Say why. That note is the only feedback the agent gets.' })
      return
    }
    setBusy(id); setOutcome(null)
    try {
      const res = await api.decideApproval(id, decision, note)
      setOutcome({ id, res })
      load()
    } catch (e) {
      setOutcome({ id, error: String(e.message || e) })
    } finally {
      setBusy(null)
    }
  }

  if (!items.length) {
    return (
      <div className="panel">
        <h2>Approvals</h2>
        <p className="muted">Nothing waiting. Run the 4x-too-high scenario to put something here.</p>
      </div>
    )
  }

  return (
    <div className="panel">
      <h2>Waiting for a buyer</h2>
      {items.map(a => {
        const act = a.proposedAction || {}
        return (
          <div className="card" key={a.id}>
            <div className="verdict">
              <b>{act.qty}</b> units of {act.sku}
              {act.unitPrice && <span className="muted"> at ${act.unitPrice}</span>}
              <span className="tag warn">{a.riskTier}</span>
            </div>

            <h3>Why this needs you</h3>
            <p>{a.reason}</p>

            <textarea placeholder="note (required to reject)"
                      value={notes[a.id] || ''}
                      onChange={e => setNotes({ ...notes, [a.id]: e.target.value })} />

            <div className="row">
              <button className="primary" disabled={busy === a.id}
                      onClick={() => decide(a.id, 'approve')}>
                {busy === a.id ? 'executing…' : 'Approve'}
              </button>
              <button disabled={busy === a.id} onClick={() => decide(a.id, 'reject')}>
                Reject
              </button>
              <button className="link" onClick={() => onOpen(a.runId)}>trace →</button>
            </div>

            {busy === a.id && (
              <p className="muted small">
                Approving hands it back to the agent, which runs the same execute and
                verify path an auto-approved action takes.
              </p>
            )}

            {outcome?.id === a.id && <Outcome o={outcome} />}
          </div>
        )
      })}
    </div>
  )
}

function Outcome({ o }) {
  if (o.error) return <pre className="error">{o.error}</pre>
  const res = o.res?.result || {}
  const v = res.verification || {}
  return (
    <div className="outcome">
      <div>
        <b>{o.res.status}</b>
        {o.res.resumed && <> · agent resumed · <b>{res.outcome}</b>
          {res.poId && <> · {res.poId}</>}</>}
      </div>
      {v.diffs && (
        <table className="diff">
          <thead><tr><th></th><th></th><th>intended</th><th>actual</th><th></th></tr></thead>
          <tbody>
            {v.diffs.map((d, i) => (
              <tr key={i} className={d.ok ? '' : 'bad'}>
                <td className="lvl">{d.level}</td>
                <td>{d.field}</td>
                <td>{d.intended}</td>
                <td>{d.actual}</td>
                <td>{d.ok ? 'ok' : 'MISMATCH'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {res.repairs?.length > 0 && (
        <p className="muted small">repair: {res.repairs.map(r => r.repair).join(' → ')}</p>
      )}
    </div>
  )
}
