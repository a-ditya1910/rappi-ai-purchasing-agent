import React, { useEffect, useState } from 'react'
import { api, SCENARIOS } from './api.js'
import Console from './Console.jsx'
import Approvals from './Approvals.jsx'
import Trace from './Trace.jsx'

export default function App() {
  const [tab, setTab] = useState('console')
  const [pending, setPending] = useState(0)
  const [openRun, setOpenRun] = useState(null)

  // poll the queue so the badge is honest without wiring sse for it
  useEffect(() => {
    const tick = () => api.approvals().then(a => setPending(a.length)).catch(() => {})
    tick()
    const t = setInterval(tick, 4000)
    return () => clearInterval(t)
  }, [])

  const show = (runId) => { setOpenRun(runId); setTab('trace') }

  return (
    <div className="app">
      <header>
        <h1>Purchasing agent</h1>
        <nav>
          <button className={tab === 'console' ? 'on' : ''} onClick={() => setTab('console')}>
            Console
          </button>
          <button className={tab === 'approvals' ? 'on' : ''} onClick={() => setTab('approvals')}>
            Approvals {pending > 0 && <span className="badge">{pending}</span>}
          </button>
          <button className={tab === 'trace' ? 'on' : ''} onClick={() => setTab('trace')}>
            Runs
          </button>
        </nav>
      </header>

      {tab === 'console' && <Console scenarios={SCENARIOS} onRun={show} />}
      {tab === 'approvals' && <Approvals onOpen={show} />}
      {tab === 'trace' && <Trace runId={openRun} onPick={setOpenRun} />}
    </div>
  )
}
