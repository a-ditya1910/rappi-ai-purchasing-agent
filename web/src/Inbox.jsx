import React, { useState } from 'react'
import { api, EMAILS } from './api.js'

/**
 * Supplier emails coming in. The model reads each one into fields, the platform
 * checks those fields against the real order, and only then does anything change.
 * A short shipment then runs the agent on what to do about the rest.
 */
export default function Inbox({ onOpen }) {
  const [pick, setPick] = useState(0)
  const [mail, setMail] = useState(EMAILS[0])
  const [busy, setBusy] = useState(false)
  const [out, setOut] = useState(null)
  const [error, setError] = useState(null)

  const choose = (i) => { setPick(i); setMail(EMAILS[i]); setOut(null); setError(null) }

  async function send() {
    setBusy(true); setOut(null); setError(null)
    try {
      setOut(await api.supplierMessage({ sender: mail.sender, subject: mail.subject, body: mail.body }))
    } catch (e) {
      setError(String(e.message || e))
    } finally {
      setBusy(false)
    }
  }

  const ev = out?.event?.data || out?.event

  return (
    <div className="two-col">
      <section className="panel">
        <h2>Incoming email</h2>
        <select value={pick} onChange={e => choose(Number(e.target.value))}>
          {EMAILS.map((m, i) => <option key={i} value={i}>{m.label}</option>)}
        </select>
        <label>From (supplier id)
          <input value={mail.sender} onChange={e => setMail({ ...mail, sender: e.target.value })} />
        </label>
        <label>Subject
          <input value={mail.subject} onChange={e => setMail({ ...mail, subject: e.target.value })} />
        </label>
        <textarea rows={9} value={mail.body} onChange={e => setMail({ ...mail, body: e.target.value })} />
        <button className="primary" onClick={send} disabled={busy}>
          {busy ? 'reading…' : 'Receive this email'}
        </button>
        <p className="muted small">
          The demo changes PO-0031 in your local database. Sending the same email twice is
          recognised and changes nothing. <code>docker compose down -v</code> restores the seed.
        </p>
      </section>

      <section className="panel">
        <h2>What happened</h2>
        {!out && !busy && !error && <p className="muted">Send an email to see it read, checked and acted on.</p>}
        {busy && <p className="muted">reading the email, then checking it against the order…</p>}
        {error && <pre className="error">{error}</pre>}

        {out?.reading && (
          <>
            <h3>1. What the model read</h3>
            <table className="checks"><tbody>
              {Object.entries(out.reading).map(([k, v]) => (
                <tr key={k}><td>{k}</td><td>{String(v ?? '—')}</td></tr>
              ))}
            </tbody></table>
          </>
        )}

        {out && (
          <>
            <h3>2. What the platform made of it</h3>
            {ev?.duplicate && <p><span className="tag">duplicate</span> already processed, nothing changed</p>}
            {ev?.applied && (
              <table className="checks"><tbody>
                <tr><td></td><td className="muted">before</td><td className="muted">after</td></tr>
                {Object.keys(ev.before).map(k => (
                  <tr key={k}><td>{k}</td><td>{String(ev.before[k])}</td><td><b>{String(ev.after[k])}</b></td></tr>
                ))}
              </tbody></table>
            )}
            {out.event && out.event.ok === false && (
              <p><span className="tag block">{out.event.error}</span> <span className="muted">{out.event.detail}</span></p>
            )}
            {!out.event && !out.reading && <p className="muted">nothing was sent to the platform</p>}
          </>
        )}

        {out && (
          <>
            <h3>3. What the agent decided</h3>
            <div className="verdict">
              <span className={'decision ' + (out.decision || '').toLowerCase()}>{out.decision}</span>
              {out.qty > 0 && <><b>{out.qty}</b> units from {out.supplierId}</>}
              {out.validation?.riskTier && <span className="tag">{out.validation.riskTier}</span>}
              {out.execution?.outcome && <span className="tag">{out.execution.outcome.replace(/_/g, ' ').toLowerCase()}</span>}
            </div>
            {out.reasoning && <p>{out.reasoning}</p>}
            {out.citations?.length > 0 && <p>{out.citations.map(c => <span key={c} className="tag">{c}</span>)}</p>}
            <div className="meta">
              {out.llmCalls} model calls · {out.toolsCalled?.length || 0} tool calls
              <button className="link" onClick={() => onOpen(out.runId)}>full trace →</button>
            </div>
          </>
        )}
      </section>
    </div>
  )
}
