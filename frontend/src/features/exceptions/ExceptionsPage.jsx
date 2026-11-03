import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { apiClient } from '../../api/client.js'

function ExceptionsPage() {
  const { exceptionId } = useParams()
  const [exceptions, setExceptions] = useState([])
  const [quarantine, setQuarantine] = useState([])
  const [detail, setDetail] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [working, setWorking] = useState(false)
  const [notice, setNotice] = useState('')
  const [resolution, setResolution] = useState({ status: 'RESOLVED', note: '', actorRef: '' })
  const [correction, setCorrection] = useState('')

  const load = useCallback(async () => {
    setLoading(true); setError('')
    try {
      const [ex, qr] = await Promise.all([
        apiClient.get('/api/v1/exceptions', { params: { page: 0, size: 50 } }),
        apiClient.get('/api/v1/quarantined-records', { params: { page: 0, size: 50 } }),
      ])
      setExceptions(ex.data.content); setQuarantine(qr.data.content)
      if (exceptionId) {
        const selected = await apiClient.get(`/api/v1/exceptions/${encodeURIComponent(exceptionId)}`)
        setDetail(selected.data)
        setResolution({ status: selected.data.resolutionStatus === 'ACCEPTED' ? 'ACCEPTED' : 'RESOLVED', note: selected.data.resolutionNote || '', actorRef: '' })
      } else setDetail(null)
    } catch (e) { setError(e.message || 'Could not load exceptions and quarantine data.') }
    finally { setLoading(false) }
  }, [exceptionId])

  useEffect(() => { load() }, [load])

  async function action(fn, message) {
    setWorking(true); setError(''); setNotice('')
    try { await fn(); setNotice(message); await load() }
    catch (e) { setError(e.message || 'The request failed.') }
    finally { setWorking(false) }
  }

  const selectedQuarantine = quarantine.find((item) => item.stagingRecordId === detail?.stagingRecordId)

  return (
    <section className="exceptions-page">
      <div className="page-heading">
        <div><div className="eyebrow">DATA QUALITY</div><h1>{exceptionId ? 'Exception detail' : 'Exceptions & quarantine'}</h1><p>Validation findings and held staging records from the backend.</p></div>
        {!exceptionId && <button className="btn btn-primary" disabled={working || loading} onClick={() => action(() => apiClient.post('/api/v1/validation/runs'), 'Fixture validation completed.')}>Run anomaly fixtures</button>}
      </div>
      {error && <div className="alert alert-danger mt-3" role="alert">{error}</div>}
      {notice && <div className="alert alert-success mt-3" role="status">{notice}</div>}
      {loading ? <div className="exception-state">Loading exception data…</div> : exceptionId ? (
        detail ? <div className="exception-detail card mt-4"><div className="card-body">
          <Link to="/exceptions">← All exceptions</Link><div className="detail-title"><h2>{detail.exceptionId}</h2><span className={`badge text-bg-${detail.severity === 'HIGH' || detail.severity === 'CRITICAL' ? 'danger' : 'secondary'}`}>{detail.severity}</span></div>
          <dl className="detail-grid"><dt>Issue</dt><dd>{detail.issueType}</dd><dt>Entity</dt><dd>{detail.entityType} · {detail.entityBusinessId || `staging ${detail.stagingRecordId}`}</dd><dt>Detected</dt><dd>{new Date(detail.detectedAt).toLocaleString()}</dd><dt>Status</dt><dd>{detail.resolutionStatus}</dd><dt>Quarantine</dt><dd>{detail.quarantineId || 'Not quarantined'} · {detail.quarantineDisposition || '—'}</dd><dt>Description</dt><dd>{detail.description}</dd></dl>
          <h3>Source payload</h3><pre>{JSON.stringify(detail.correctedPayload || detail.originalPayload, null, 2)}</pre>
          {detail.resolutionStatus === 'OPEN' || detail.resolutionStatus === 'IN_REVIEW' ? <div className="resolution-form"><h3>Resolve exception</h3><div className="row g-2"><div className="col-md-3"><select className="form-select" value={resolution.status} onChange={(e) => setResolution({ ...resolution, status: e.target.value })}><option>RESOLVED</option><option>ACCEPTED</option></select></div><div className="col-md-4"><input className="form-control" placeholder="Actor reference (optional)" value={resolution.actorRef} onChange={(e) => setResolution({ ...resolution, actorRef: e.target.value })}/></div><div className="col-md-5"><input className="form-control" placeholder="Resolution note (required)" value={resolution.note} onChange={(e) => setResolution({ ...resolution, note: e.target.value })}/></div></div><button className="btn btn-outline-primary mt-2" disabled={working || !resolution.note.trim()} onClick={() => action(() => apiClient.post(`/api/v1/exceptions/${detail.exceptionId}/resolution`, resolution), 'Resolution audit recorded.')}>Save resolution</button></div> : <p className="text-muted">Resolved {detail.resolutionAuditedAt ? new Date(detail.resolutionAuditedAt).toLocaleString() : detail.resolvedAt ? new Date(detail.resolvedAt).toLocaleString() : ''}{detail.resolutionActorRef ? ` by ${detail.resolutionActorRef}` : ''}: {detail.resolutionNote}</p>}
          {selectedQuarantine && selectedQuarantine.disposition !== 'RELEASED' && <div className="resolution-form"><h3>Quarantine correction</h3><textarea className="form-control" rows="5" aria-label="Corrected JSON payload" value={correction || JSON.stringify(detail.correctedPayload || detail.originalPayload, null, 2)} onChange={(e) => setCorrection(e.target.value)}/><div className="d-flex gap-2 mt-2"><button className="btn btn-outline-secondary" disabled={working} onClick={() => action(() => apiClient.put(`/api/v1/quarantined-records/${selectedQuarantine.quarantineId}/correction`, JSON.parse(correction || JSON.stringify(detail.correctedPayload || detail.originalPayload))), 'Corrected payload stored; revalidation is required.')}>Save correction</button><button className="btn btn-outline-primary" disabled={working} onClick={() => action(() => apiClient.post(`/api/v1/quarantined-records/${selectedQuarantine.quarantineId}/revalidate`), 'Complete revalidation finished.')}>Revalidate</button><button className="btn btn-success" disabled={working || detail.validationStatus !== 'VALID'} onClick={() => action(() => apiClient.post(`/api/v1/quarantined-records/${selectedQuarantine.quarantineId}/release`, { note: 'Released after revalidation', actorRef: null }), 'Quarantine released after validation.')}>Release</button></div><small className="text-muted">Release is enabled after the current corrected payload passes validation and its exception is resolved.</small></div>}
        </div></div> : <div className="exception-state">Exception not found.</div>
      ) : <>
        <div className="table-card mt-4"><div className="table-heading"><h2>Exceptions</h2><span>{exceptions.length} records</span></div>{exceptions.length === 0 ? <div className="exception-state">No exceptions returned by the backend.</div> : <div className="table-responsive"><table className="table align-middle mb-0"><thead><tr><th>Issue / severity</th><th>Entity reference</th><th>Detected</th><th>Resolution</th><th>Quarantine</th></tr></thead><tbody>{exceptions.map((item) => <tr key={item.exceptionId}><td><Link to={`/exceptions/${item.exceptionId}`}>{item.issueType}</Link><small className="d-block text-muted">{item.severity}</small></td><td>{item.entityType}<small className="d-block text-muted">{item.entityBusinessId || item.stagingRecordId}</small></td><td>{new Date(item.detectedAt).toLocaleString()}</td><td>{item.resolutionStatus}</td><td>{item.quarantineDisposition || '—'}</td></tr>)}</tbody></table></div>}</div>
        <div className="table-card mt-4"><div className="table-heading"><h2>Quarantined records</h2><span>{quarantine.length} records</span></div>{quarantine.length === 0 ? <div className="exception-state">No quarantined records returned by the backend.</div> : <div className="table-responsive"><table className="table align-middle mb-0"><thead><tr><th>Quarantine</th><th>Fixture / entity</th><th>Validation</th><th>Disposition</th><th>Detected</th></tr></thead><tbody>{quarantine.map((item) => <tr key={item.quarantineId}><td>{item.quarantineId}</td><td>{item.fixtureCode}<small className="d-block text-muted">{item.entityType}</small></td><td>{item.validationStatus}</td><td>{item.disposition}</td><td>{new Date(item.quarantinedAt).toLocaleString()}</td></tr>)}</tbody></table></div>}</div>
      </>}
    </section>
  )
}

export default ExceptionsPage
