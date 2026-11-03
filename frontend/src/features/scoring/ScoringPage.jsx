import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { apiClient } from '../../api/client.js'

const PAGE_SIZE = 20
const EMPTY = { content: [], totalElements: 0, totalPages: 0 }
const components = ['DELIVERY', 'QUALITY', 'COST', 'CAPACITY', 'COMPLIANCE', 'DISRUPTION']

export default function ScoringPage() {
  const [searchParams] = useSearchParams()
  const [runs, setRuns] = useState(EMPTY)
  const [scores, setScores] = useState(EMPTY)
  const [runId, setRunId] = useState(() => searchParams.get('runId') || '')
  const [summary, setSummary] = useState(null)
  const [classification, setClassification] = useState('')
  const [minimumScore, setMinimumScore] = useState('')
  const [maximumScore, setMaximumScore] = useState('')
  const [scorePage, setScorePage] = useState(0)
  const [supplierId, setSupplierId] = useState(() => searchParams.get('supplierId') || '')
  const [scorecard, setScorecard] = useState(null)
  const [history, setHistory] = useState(EMPTY)
  const [evidence, setEvidence] = useState(EMPTY)
  const [evidencePage, setEvidencePage] = useState(0)
  const [componentFilter, setComponentFilter] = useState('')
  const [includedFilter, setIncludedFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState('')

  const refreshRuns = useCallback(async () => {
    const { data } = await apiClient.get('/api/v1/score-runs', { params: { page: 0, size: 50 } })
    setRuns(data)
    if (!runId && data.content?.length) setRunId(data.content[0].runId)
  }, [runId])

  useEffect(() => {
    let active = true
    refreshRuns().catch((err) => active && setError(err.message)).finally(() => active && setLoading(false))
    return () => { active = false }
  }, [refreshRuns])

  const loadRun = useCallback(async () => {
    if (!runId) return
    try {
      const [summaryResponse, scoresResponse] = await Promise.all([
        apiClient.get(`/api/v1/score-runs/${runId}/summary`),
        apiClient.get(`/api/v1/score-runs/${runId}/scores`, { params: { page: scorePage, size: PAGE_SIZE, classification: classification || undefined, minScore: minimumScore === '' ? undefined : Number(minimumScore), maxScore: maximumScore === '' ? undefined : Number(maximumScore) } }),
      ])
      setSummary(summaryResponse.data)
      setScores(scoresResponse.data)
      setError('')
    } catch (err) { setError(err.message) }
  }, [runId, scorePage, classification, minimumScore, maximumScore])

  useEffect(() => {
    if (!runId) return undefined
    loadRun()
    const timer = setInterval(() => { loadRun(); refreshRuns().catch(() => {}) }, 3000)
    return () => clearInterval(timer)
  }, [runId, loadRun, refreshRuns])

  useEffect(() => {
    let active = true
    if (!runId || !supplierId) { setScorecard(null); setEvidence(EMPTY); setHistory(EMPTY); return undefined }
    setDetailLoading(true)
    Promise.all([
      apiClient.get(`/api/v1/score-runs/${runId}/scores/${supplierId}`),
      apiClient.get(`/api/v1/score-runs/${runId}/scores/${supplierId}/evidence`, { params: {
        component: componentFilter || undefined,
        included: includedFilter === '' ? undefined : includedFilter === 'true',
        page: evidencePage, size: PAGE_SIZE,
      } }),
      apiClient.get(`/api/v1/suppliers/${supplierId}/score-history`, { params: { page: 0, size: 20 } }),
    ]).then(([scoreResponse, evidenceResponse, historyResponse]) => {
      if (active) { setScorecard(scoreResponse.data); setEvidence(evidenceResponse.data); setHistory(historyResponse.data) }
    }).catch((err) => { if (active) setError(err.message) }).finally(() => active && setDetailLoading(false))
    return () => { active = false }
  }, [runId, supplierId, componentFilter, includedFilter, evidencePage])

  const startRun = async () => {
    setStarting(true); setError('')
    try {
      const { data } = await apiClient.post('/api/v1/score-runs', {}, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
      setRunId(data.runId); setScorePage(0); setSupplierId(''); await refreshRuns()
    } catch (err) { setError(err.message) } finally { setStarting(false) }
  }

  const download = async (report, format) => {
    try {
      const { data } = await apiClient.get(`/api/v1/score-runs/${runId}/export`, { params: { report, format, page: 0, size: 10000 }, responseType: 'blob' })
      const url = URL.createObjectURL(data); const a = document.createElement('a')
      a.href = url; a.download = `yottaflex-${runId}-${report}.${format}`; a.click(); URL.revokeObjectURL(url)
    } catch (err) { setError(err.message) }
  }

  return <section>
    <div className="page-heading"><div><div className="eyebrow">SUPPLIER PERFORMANCE</div><h1>Reliability scoring</h1><p>Persisted scoring runs, component calculations, and source evidence.</p></div><button className="btn btn-primary" disabled={starting} onClick={startRun}>{starting ? 'Starting…' : 'Start scoring run'}</button></div>
    {error && <div className="alert alert-danger mt-3" role="alert">{error}</div>}
    {loading ? <div className="empty-state"><p>Loading scoring runs…</p></div> : runs.content.length === 0 ? <div className="empty-state"><h2>No scoring runs yet</h2><p>Start a run to calculate scores from persisted operational records.</p></div> : <>
      <div className="card mt-4"><div className="card-header d-flex flex-wrap align-items-center justify-content-between gap-2"><strong>Run history</strong><select aria-label="Selected score run" className="form-select w-auto" value={runId} onChange={(e) => { setRunId(e.target.value); setSupplierId(''); setScorePage(0) }}>{runs.content.map((run) => <option key={run.runId} value={run.runId}>{run.runId} · {run.status} · {run.algorithmVersion}</option>)}</select></div>
        {summary && <div className="card-body"><div className="row g-3"><Metric title="Run status" value={summary.status}/><Metric title="Suppliers evaluated" value={summary.totalSuppliers}/><Metric title="Valid scores" value={summary.validScores}/><Metric title="Insufficient data" value={summary.insufficientData}/><Metric title="Average valid score" value={summary.averageScore == null ? '—' : Number(summary.averageScore).toFixed(2)}/><Metric title="Critical compliance" value={summary.criticalComplianceCount}/><Metric title="Cutoff" value={summary.asOf ? new Date(summary.asOf).toLocaleString() : '—'}/><Metric title="Algorithm" value={summary.algorithmVersion}/><Metric title="Run duration" value={summary.durationMillis == null ? '—' : `${summary.durationMillis} ms`}/></div>{summary.classifications?.length > 0 && <p className="small text-muted mt-3 mb-0">Classification distribution: {summary.classifications.map((x) => `${x.classification}: ${x.count}`).join(' · ')}</p>}{summary.failureReason && <div className="alert alert-danger mt-3">{summary.failureReason}</div>}
          {summary.status === 'COMPLETED' && <div className="d-flex gap-2 flex-wrap mt-3">{['scores','components','evidence','exclusions','run'].map((report) => <div className="btn-group" key={report}><button className="btn btn-outline-secondary btn-sm" onClick={() => download(report, 'json')}>{report} JSON</button>{report !== 'run' && <button className="btn btn-outline-secondary btn-sm" onClick={() => download(report, 'csv')}>{report} CSV</button>}</div>)}</div>}
        </div>}</div>

      <div className="card mt-4"><div className="card-header d-flex flex-wrap align-items-center justify-content-between gap-2"><strong>Supplier rankings</strong><div className="d-flex flex-wrap gap-2"><select className="form-select w-auto" aria-label="Filter classification" value={classification} onChange={(e) => { setClassification(e.target.value); setScorePage(0) }}><option value="">All classifications</option>{['EXCELLENT','GOOD','MODERATE','POOR','CRITICAL','INSUFFICIENT_DATA'].map((value) => <option key={value}>{value}</option>)}</select><input className="form-control w-auto" type="number" min="0" max="100" placeholder="Min score" aria-label="Minimum score" value={minimumScore} onChange={(e) => { setMinimumScore(e.target.value); setScorePage(0) }}/><input className="form-control w-auto" type="number" min="0" max="100" placeholder="Max score" aria-label="Maximum score" value={maximumScore} onChange={(e) => { setMaximumScore(e.target.value); setScorePage(0) }}/></div></div>
        <div className="table-responsive"><table className="table table-hover mb-0"><thead><tr><th>Supplier</th><th>Name</th><th>Score</th><th>Classification</th><th>Coverage</th><th>Critical compliance</th><th /></tr></thead><tbody>{scores.content.map((row) => <tr key={row.supplierId}><td>{row.supplierId}</td><td>{row.name}</td><td>{row.score ?? '—'}</td><td>{row.classification}</td><td>{row.coverage}%</td><td>{row.criticalComplianceViolation ? 'Yes' : 'No'}</td><td><button className="btn btn-sm btn-outline-primary" onClick={() => { setSupplierId(row.supplierId); setEvidencePage(0) }}>Scorecard</button></td></tr>)}</tbody></table></div>
        {scores.content.length === 0 && <div className="p-4 text-muted">No supplier scores match this filter.</div>}
        <Pager page={scorePage} totalPages={scores.totalPages || 0} onChange={setScorePage}/>
      </div>

      {supplierId && <div className="card mt-4"><div className="card-header d-flex justify-content-between align-items-center"><strong>Supplier scorecard</strong><span>{supplierId} {scorecard?.name ? `· ${scorecard.name}` : ''}</span></div>
        {detailLoading ? <div className="p-4 text-muted">Loading scorecard…</div> : scorecard && <div className="card-body"><div className="row g-3 mb-4"><Metric title="Overall score" value={scorecard.score ?? 'Insufficient data'}/><Metric title="Classification" value={scorecard.classification}/><Metric title="Weight coverage" value={`${scorecard.coverage}%`}/><Metric title="Critical compliance" value={scorecard.criticalComplianceViolation ? 'Escalated' : 'No critical failure'}/></div><p>{scorecard.explanation}</p>
          {(() => { const c = (scorecard.components || []).find((component) => component.component === 'COMPLIANCE'); const m = c?.metrics || {}; return <section className="border rounded p-3 mb-4" aria-label="Compliance summary"><h3 className="h5">Compliance summary</h3><div className="row g-2"><Metric title="Applicable requirements" value={m.totalApplicableRequirements}/><Metric title="Mandatory" value={m.mandatoryRequirements}/><Metric title="Critical" value={m.criticalRequirements}/><Metric title="Supplier scopes" value={m.supplierScopes}/><Metric title="Site scopes" value={m.siteScopes}/><Metric title="Passed" value={m.passedRequirements}/><Metric title="Failed" value={m.failedRequirements}/><Metric title="Pending" value={m.pendingRequirements}/><Metric title="Expired" value={m.expiredRequirements}/><Metric title="Missing evidence" value={m.missingEvidence}/><Metric title="Legacy evidence unknown" value={m.legacyEvidenceUnknownCount}/><Metric title="Not applicable" value={m.notApplicableRequirements}/><Metric title="Mandatory critical missing" value={m.mandatoryCriticalMissingCount}/><Metric title="Coverage" value={m.coveragePercentage == null ? undefined : `${m.coveragePercentage}%`}/></div>{m.criticalViolation && <div className="alert alert-danger mt-3 mb-0">A mandatory critical compliance issue is escalated.</div>}{m.escalationReasons?.length > 0 && <p className="small mt-3 mb-0">Escalation details: {m.escalationReasons.join(' · ')}</p>}</section> })()}
          <div className="table-responsive"><table className="table table-sm"><thead><tr><th>Component</th><th>Score</th><th>Status</th><th>Numerator</th><th>Denominator</th><th>Observations</th><th>Eligible</th><th>Excluded</th><th>Explanation</th></tr></thead><tbody>{(scorecard.components || []).map((c) => <tr key={c.component}><td>{c.component}</td><td>{c.score ?? '—'}</td><td>{c.status}</td><td>{c.numerator ?? '—'}</td><td>{c.denominator ?? '—'}</td><td>{c.observationCount}</td><td>{c.eligibleObservationCount}</td><td>{c.excludedObservationCount}</td><td>{c.explanation || c.missingReason || '—'}{c.metrics && <details><summary>Metrics</summary><pre className="small">{JSON.stringify(c.metrics,null,2)}</pre></details>}</td></tr>)}</tbody></table></div>
          <h3 className="h5 mt-4">Source evidence</h3><div className="d-flex flex-wrap gap-2 mb-3"><select className="form-select w-auto" aria-label="Filter evidence component" value={componentFilter} onChange={(e) => { setComponentFilter(e.target.value); setEvidencePage(0) }}><option value="">All components</option>{components.map((c) => <option key={c}>{c}</option>)}</select><select className="form-select w-auto" aria-label="Filter evidence disposition" value={includedFilter} onChange={(e) => { setIncludedFilter(e.target.value); setEvidencePage(0) }}><option value="">Included and excluded</option><option value="true">Included</option><option value="false">Excluded</option></select></div>
          {evidence.content.length === 0 ? <p className="text-muted">No evidence matches these filters.</p> : <div className="table-responsive"><table className="table table-sm"><thead><tr><th>Component</th><th>Source</th><th>Business ID</th><th>Record ID</th><th>Disposition</th><th>Contribution</th><th>Reason</th><th>Evaluated</th><th>Details</th></tr></thead><tbody>{evidence.content.map((e) => <tr key={`${e.sourceEntityType}-${e.sourceRecordId}`}><td>{e.component}</td><td>{e.sourceEntityType}</td><td>{e.sourceBusinessId}</td><td>{e.sourceRecordId}</td><td>{e.included ? 'Included' : 'Excluded'}</td><td>{e.contribution ?? '—'}</td><td>{e.exclusionReason || '—'}</td><td>{e.evaluatedAt ? new Date(e.evaluatedAt).toLocaleString() : '—'}</td><td><details><summary>View</summary><pre className="small mb-0">{JSON.stringify(e.details || {}, null, 2)}</pre></details></td></tr>)}</tbody></table></div>}
          <Pager page={evidencePage} totalPages={evidence.totalPages || 0} onChange={setEvidencePage}/>
          <h3 className="h5 mt-4">Historical scores</h3>{history.content?.length ? <div className="table-responsive"><table className="table table-sm"><thead><tr><th>Run</th><th>Algorithm</th><th>Cutoff</th><th>Score</th><th>Classification</th><th>Coverage</th></tr></thead><tbody>{history.content.map((item) => <tr key={item.runId}><td>{item.runId}</td><td>{item.algorithmVersion}</td><td>{item.asOf ? new Date(item.asOf).toLocaleString() : '—'}</td><td>{item.score ?? '—'}</td><td>{item.classification}</td><td>{item.coverage}%</td></tr>)}</tbody></table></div> : <p className="text-muted">No completed score history is available.</p>}
        </div>}
      </div>}
    </>}
  </section>
}

function Metric({ title, value }) { return <div className="col-6 col-md-4 col-xl-3"><div className="border rounded p-3 h-100"><div className="small text-muted">{title}</div><strong>{value ?? '—'}</strong></div></div> }
function Pager({ page, totalPages, onChange }) { return <div className="d-flex justify-content-between align-items-center p-3"><button className="btn btn-sm btn-outline-secondary" disabled={page <= 0} onClick={() => onChange(page - 1)}>Previous</button><span className="small text-muted">Page {totalPages ? page + 1 : 0} of {totalPages}</span><button className="btn btn-sm btn-outline-secondary" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>Next</button></div> }
