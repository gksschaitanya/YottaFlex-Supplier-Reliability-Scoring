import { useEffect } from 'react'
import { FiDatabase } from 'react-icons/fi'
import { useDispatch, useSelector } from 'react-redux'
import { loadBackendHealth } from '../system/systemSlice.js'

function OverviewPage() {
  const dispatch = useDispatch()
  const { backendStatus, healthError } = useSelector((state) => state.system)

  useEffect(() => {
    dispatch(loadBackendHealth())
  }, [dispatch])

  return (
    <section className="overview-page" aria-labelledby="page-title">
      <div className="page-heading">
        <div>
          <div className="eyebrow">SUPPLIER OPERATIONS</div>
          <h1 id="page-title">Overview</h1>
          <p>Reliability workspace</p>
        </div>
        <div className={`connection-pill connection-${backendStatus}`} role="status">
          <span className={`status-dot status-${backendStatus}`} />
          {backendStatus === 'checking' ? 'Connecting to API' : `API ${backendStatus}`}
        </div>
      </div>

      <div className="empty-state">
        <div className="empty-state-icon"><FiDatabase aria-hidden="true" /></div>
        <h2>No supplier data yet</h2>
        <p>Operational records and performance indicators will appear here when the data workflows are available.</p>
        {healthError && <div className="health-error">Backend connection: {healthError}</div>}
      </div>
    </section>
  )
}

export default OverviewPage
