import React, { useEffect, useState } from 'react'
import { api } from './api.js'

/**
 * Left: what a buyer would be looking at. Right: what the agent did about it.
 *
 * The recommended quantity is editable on purpose. Typing 50000 and watching
 * the agent refuse it says more about the system than any amount of prose.
 */
export default function Console({ scenarios, onRun, active }) {
  const [pick, setPick] = useState(0)
  const [recommended, setRecommended] = useState(scenarios[0].recommended ?? '')
  const [situation, setSituation] = useState(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [arrived, setArrived] = useState('')
  const [receipt, setReceipt] = useState(null)
  const [reload, setReload] = useState(0)
  const [sold, setSold] = useState('')

  const s = scenarios[pick]

  useEffect(() => {
    setRecommended(s.recommended ?? '')
    setResult(null)
    setError(null)
    setReceipt(null)
  }, [pick])

  // also reloads on coming back to the tab - an approval may have moved stock
  useEffect(() => {
    if (!active) return
    setSituation(null)

    Promise.all([
      api.product(s.sku).catch(() => null),
      api.inventory(s.sku, s.node).catch(() => null),
      api.forecast(s.sku, s.node).catch(() => null),
      api.openPos(s.sku, s.node).catch(() => []),
      api.budget(s.node, s.category).catch(() => null),
      api.anomaly(s.sku, s.node).catch(() => null),
      api.transfersIncoming(s.sku, s.node).catch(() => []),
    ]).then(([product, inv, fc, pos, budget, anomaly, tos]) =>
      setSituation({ product, inv, fc, pos, budget, anomaly, tos }))
  }, [pick, reload, active])

  // a delivery arriving: stock and money move, and the supplier's reliability
  // learns from what actually turned up
  async function receive(po) {
    setReceipt(null)
    try {
      const qty = po.qtyConfirmed ?? po.qtyOrdered
      const r = await api.receivePo(po.poId, qty, arrived)
      setReceipt(`${po.poId}: ${r.qtyReceived} received ${r.onTime ? 'on time' : 'late'} ` +
        `(promised ${r.promised}). ${r.supplierId} reliability ${r.reliabilityBefore} → ${r.reliabilityAfter}`)
      setReload(n => n + 1)
    } catch (e) {
      setReceipt(String(e.message || e))
    }
  }

  async function cancel(po) {
    try {
      const r = await api.cancelPo(po.poId)
      setReceipt(`${r.message}. Its quantity and budget are released.`)
      setReload(n => n + 1)
    } catch (e) {
      setReceipt(String(e.message || e))
    }
  }

  async function sell() {
    if (!sold) return
    try {
      const r = await api.sell(s.sku, s.node, Number(sold))
      setReceipt(`Sold ${r.sold}. ${r.onHand} left on hand.`)
      setSold('')
      setReload(n => n + 1)
    } catch (e) {
      setReceipt(String(e.message || e))
    }
  }

  async function receiveTransfer(t) {
    try {
      const r = await api.receiveTransfer(t.id)
      setReceipt(r.message)
      setReload(n => n + 1)
    } catch (e) {
      setReceipt(String(e.message || e))
    }
  }

  async function run() {
    setBusy(true); setError(null); setResult(null)
    try {
      const { runId } = await api.startRun({
        scenario: s.id, sku: s.sku, nodeId: s.node, supplierId: s.supplier,
        recommendedQty: recommended === '' ? null : Number(recommended),
      })
      const out = await api.decide({
        run_id: runId, scenario: s.id, sku: s.sku, node_id: s.node,
        supplier_id: s.supplier,
        recommended_qty: recommended === '' ? null : Number(recommended),
      })
      setResult({ ...out, runId })
    } catch (e) {
      setError(String(e.message || e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="two-col">
      <section className="panel">
        <h2>The situation</h2>

        <select value={pick} onChange={e => setPick(Number(e.target.value))}>
          {scenarios.map((x, i) => <option key={i} value={i}>{x.label}</option>)}
        </select>
        <p className="note">{s.note}</p>

        {!situation && <p className="muted">loading…</p>}
        {situation && (
          <>
            <dl>
              <dt>SKU</dt><dd>{s.sku}{situation.product?.isPerishable &&
                <span className="tag">perishable, {situation.product.shelfLifeDays}d</span>}</dd>
              <dt>Position</dt>
              <dd>
                <b>{situation.inv ? situation.inv.position : '—'}</b>
                {situation.inv &&
                  <span className="muted"> = {situation.inv.onHand} on hand
                    − {situation.inv.reserved} reserved
                    + {situation.inv.inTransit} in transit</span>}
              </dd>
              <dt>Sold</dt>
              <dd>
                <input className="qty" value={sold} placeholder="qty"
                       onChange={e => setSold(e.target.value.replace(/[^0-9]/g, ''))} />
                <button className="link" onClick={sell}>record sale</button>
                <span className="muted small"> stands in for the till. lowers the position</span>
              </dd>
              <dt>Forecast</dt>
              <dd>{situation.fc ? `${situation.fc.meanPerDay}/day` : '—'}
                {situation.fc?.isStale && <span className="tag warn">stale</span>}</dd>
              <dt>Budget</dt>
              <dd>{situation.budget ? `$${situation.budget.available} of $${situation.budget.allocated}` : '—'}</dd>
              <dt>Open POs</dt>
              <dd>{situation.pos?.length ? situation.pos.map(p => (
                <div key={p.poId}>
                  {p.poId}: {p.qtyConfirmed ?? p.qtyOrdered} from {p.supplierId} in {p.daysOut}d
                  <button className="link" onClick={() => receive(p)}>receive</button>
                  <button className="link" onClick={() => cancel(p)}>cancel</button>
                </div>
              )) : 'none'}</dd>
              {situation.tos?.length > 0 && (
                <>
                  <dt>Transfers</dt>
                  <dd>{situation.tos.map(t => (
                    <div key={t.id}>
                      {t.id}: {t.qty} from {t.fromNode}, arrives {t.expectedArrival}
                      <button className="link" onClick={() => receiveTransfer(t)}>receive</button>
                    </div>
                  ))}</dd>
                </>
              )}
              <dt>Arrived on</dt>
              <dd>
                <input type="date" value={arrived} onChange={e => setArrived(e.target.value)} />
                <span className="muted small"> blank = today. a later date makes it late, and the supplier's score drops</span>
              </dd>
              {situation.anomaly && (
                <>
                  <dt>Demand</dt>
                  <dd>
                    <span className={'tag ' + (situation.anomaly.verdict === 'REAL' ? 'warn' : '')}>
                      {situation.anomaly.verdict}
                    </span>
                    <span className="muted"> TS {situation.anomaly.trackingSignal},
                      sustained {situation.anomaly.sustainedDays}d
                      {situation.anomaly.promoOverlap && ', promo running'}</span>
                  </dd>
                </>
              )}
            </dl>

            {receipt && <p className="note">{receipt}</p>}

            <label className="rec">
              Recommended quantity
              <input value={recommended} placeholder="none"
                     onChange={e => setRecommended(e.target.value.replace(/[^0-9]/g, ''))} />
            </label>
            <p className="muted small">Editable. Try 50000 and see what it does.</p>

            <button className="primary" onClick={run} disabled={busy}>
              {busy ? 'thinking…' : 'Run the agent'}
            </button>
          </>
        )}
      </section>

      <section className="panel">
        <h2>What the agent did</h2>
        {!result && !busy && !error && <p className="muted">Run a scenario to see the decision.</p>}
        {busy && <p className="muted">calling the model, this takes 10–30 seconds…</p>}
        {error && <pre className="error">{error}</pre>}
        {result && <Result r={result} onOpen={onRun} />}
      </section>
    </div>
  )
}

function Result({ r, onOpen }) {
  const v = r.validation || {}
  const plan = r.analysis || {}
  const exec = r.execution || {}

  return (
    <div>
      <div className="verdict">
        <span className={'decision ' + (r.decision || '').toLowerCase()}>{r.decision}</span>
        <b>{r.qty}</b> units
        {v.riskTier && <span className={'tag ' + (v.riskTier === 'T3' ? 'warn' : 'ok')}>{v.riskTier}</span>}
        {exec.outcome && <span className="tag">{exec.outcome.replace(/_/g, ' ').toLowerCase()}</span>}
      </div>

      {plan.explanationSteps && (
        <>
          <h3>How the quantity was worked out</h3>
          <pre className="steps">{plan.explanationSteps.join('\n')}</pre>
          <p className="muted small">
            Computed in Java before the model saw the recommendation, so it cannot
            anchor on it and reason backwards.
          </p>
        </>
      )}

      {r.reasoning && (<><h3>Why</h3><p>{r.reasoning}</p></>)}

      {r.keyFactors?.length > 0 && (
        <><h3>Key factors</h3>
          <ul>{r.keyFactors.map((f, i) => <li key={i}>{f}</li>)}</ul></>
      )}

      {r.citations?.length > 0 && (
        <><h3>Sources</h3>
          <p>{r.citations.map(c => <span key={c} className="tag">{c}</span>)}</p>
          <p className="muted small">Retrieved from the knowledge base at decision time.</p></>
      )}

      {r.assumptions?.length > 0 && (
        <><h3>Taken on faith</h3>
          <ul className="muted">{r.assumptions.map((a, i) => <li key={i}>{a}</li>)}</ul></>
      )}

      {v.checks && (
        <>
          <h3>Constraint checks</h3>
          <table className="checks">
            <tbody>
              {v.checks.filter(c => c.status !== 'PASS').map(c => (
                <tr key={c.id}>
                  <td><span className={'tag ' + c.status.toLowerCase()}>{c.status}</span></td>
                  <td>{c.id}</td>
                  <td className="muted">{c.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="muted small">
            {v.checks.filter(c => c.status === 'PASS').length} other checks passed.
            Tier is computed in Java; the model is never asked what it is allowed to do.
          </p>
        </>
      )}

      <div className="meta">
        {r.llmCalls} model calls · {r.toolsCalled?.length || 0} tool calls ·
        {' '}{r.gatherTurns} gather turns · {Math.round((r.durationMs || 0) / 100) / 10}s
        <button className="link" onClick={() => onOpen(r.runId)}>full trace →</button>
      </div>
    </div>
  )
}
