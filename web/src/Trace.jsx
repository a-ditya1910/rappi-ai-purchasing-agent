import React, { useEffect, useState } from 'react'
import { api } from './api.js'

/**
 * The audit trail. Every row here was written by the platform's interceptor
 * because the agent's tool calls happen to be http requests - the agent has no
 * tracing code of its own.
 */
export default function Trace({ runId, onPick }) {
  const [runs, setRuns] = useState([])
  const [run, setRun] = useState(null)

  useEffect(() => { api.runs().then(setRuns).catch(() => setRuns([])) }, [runId])

  useEffect(() => {
    if (!runId) { setRun(null); return }
    const load = () => api.run(runId).then(setRun).catch(() => {})
    load()
    // poll while it is still going, so a run started elsewhere fills in live
    const t = setInterval(() => {
      if (run?.status === 'RUNNING') load()
    }, 2000)
    return () => clearInterval(t)
  }, [runId, run?.status])

  return (
    <div className="two-col">
      <section className="panel">
        <h2>Runs</h2>
        {!runs.length && <p className="muted">No runs yet.</p>}
        <ul className="runs">
          {runs.map(r => (
            <li key={r.runId} className={r.runId === runId ? 'on' : ''}
                onClick={() => onPick(r.runId)}>
              <span className={'decision ' + (r.decision || '').toLowerCase()}>
                {r.decision || r.status}
              </span>
              <span>{r.sku}</span>
              <span className="muted small">{r.runId.slice(0, 8)}</span>
            </li>
          ))}
        </ul>
      </section>

      <section className="panel">
        <h2>Trace</h2>
        {!run && <p className="muted">Pick a run.</p>}
        {run && (
          <>
            <div className="verdict">
              <span className={'decision ' + (run.decision || '').toLowerCase()}>
                {run.decision || run.status}
              </span>
              {run.finalQty != null && <><b>{run.finalQty}</b> units</>}
            </div>

            {run.explanation && <p>{run.explanation}</p>}

            <h3>Steps</h3>
            <table className="steps-table">
              <tbody>
                {run.steps?.map(s => (
                  <tr key={s.seq}>
                    <td className="muted">{s.seq}</td>
                    <td><span className="tag small">{s.type.toLowerCase()}</span></td>
                    <td className="mono">{s.name}</td>
                    <td className="muted">{s.latencyMs != null ? s.latencyMs + 'ms' : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="muted small">
              {run.steps?.length || 0} steps, all written by one Spring interceptor.
            </p>
          </>
        )}
      </section>
    </div>
  )
}
