import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { apiClient } from '../../api/client.js'

const PAGE_SIZE = 20

export default function SuppliersPage() {
  const [page, setPage] = useState(0)
  const [result, setResult] = useState({ content: [], totalPages: 0, totalElements: 0 })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)

  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    apiClient.get('/api/v1/suppliers', { params: { page, size: PAGE_SIZE } })
      .then(({ data }) => { if (active) setResult(data) })
      .catch((err) => { if (active) setError(err.message) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [page, refresh])

  return <section aria-labelledby="suppliers-title">
    <div className="page-heading"><div><div className="eyebrow">SUPPLIER OPERATIONS</div><h1 id="suppliers-title">Supplier records</h1><p>Supplier identity and latest completed reliability score.</p></div></div>
    {loading ? <div className="empty-state" role="status">Loading suppliers…</div>
      : error ? <div className="empty-state" role="alert"><h2>Could not load suppliers</h2><p>{error}</p><button className="btn btn-outline-primary" onClick={() => setRefresh((value) => value + 1)}>Retry</button></div>
        : result.content.length === 0 ? <div className="empty-state"><h2>No suppliers found</h2><p>Supplier records will appear here when they are available from the backend.</p></div>
        : <div className="card mt-4"><div className="table-responsive"><table className="table table-hover mb-0">
          <thead><tr><th>Supplier</th><th>Identifier</th><th>Status</th><th>Latest score</th><th>Classification</th><th>Scorecard</th></tr></thead>
          <tbody>{result.content.map((supplier) => {
            const scorecardUrl = supplier.scoreRunId
              ? `/scoring?runId=${encodeURIComponent(supplier.scoreRunId)}&supplierId=${encodeURIComponent(supplier.supplierId)}`
              : `/scoring?supplierId=${encodeURIComponent(supplier.supplierId)}`
            return <tr key={supplier.supplierId}>
              <td>{supplier.name}</td><td>{supplier.supplierId}</td><td>{supplier.status}</td>
              <td>{supplier.score ?? 'No completed score'}</td><td>{supplier.classification ?? '—'}</td>
              <td><Link className="btn btn-sm btn-outline-primary" to={scorecardUrl}>View scorecard</Link></td>
            </tr>
          })}</tbody>
        </table></div>
        <div className="d-flex justify-content-between align-items-center p-3">
          <span className="small text-muted">{result.totalElements} suppliers · Page {result.totalPages ? page + 1 : 0} of {result.totalPages}</span>
          <div className="d-flex gap-2"><button className="btn btn-sm btn-outline-secondary" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>Previous</button>
            <button className="btn btn-sm btn-outline-secondary" disabled={page + 1 >= result.totalPages} onClick={() => setPage((value) => value + 1)}>Next</button></div>
        </div></div>}
  </section>
}
