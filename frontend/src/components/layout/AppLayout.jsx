import { NavLink, Outlet } from 'react-router-dom'
import { FiActivity, FiAlertCircle, FiGrid, FiPackage, FiTrendingUp } from 'react-icons/fi'
import { useSelector } from 'react-redux'

function AppLayout() {
  const backendStatus = useSelector((state) => state.system.backendStatus)

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-lockup">
          <span className="brand-mark" aria-hidden="true">Y</span>
          <span className="brand-name">YottaFlex<span>SUPPLIER INTELLIGENCE</span></span>
        </div>
        <div className="nav-caption">WORKSPACE</div>
        <nav aria-label="Primary navigation">
          <NavLink className={({ isActive }) => `nav-item${isActive ? ' is-active' : ''}`} end to="/">
            <FiGrid aria-hidden="true" />
            <span>Overview</span>
          </NavLink>
          <NavLink className={({ isActive }) => `nav-item${isActive ? ' is-active' : ''}`} to="/suppliers">
            <FiPackage aria-hidden="true" />
            <span>Supplier records</span>
          </NavLink>
          <NavLink className={({ isActive }) => `nav-item${isActive ? ' is-active' : ''}`} to="/exceptions">
            <FiAlertCircle aria-hidden="true" />
            <span>Exceptions</span>
          </NavLink>
          <NavLink className={({ isActive }) => `nav-item${isActive ? ' is-active' : ''}`} to="/scoring">
            <FiTrendingUp aria-hidden="true" />
            <span>Scoring</span>
          </NavLink>
        </nav>
        <div className="sidebar-bottom">
          <div className="nav-caption">SYSTEM</div>
          <div className="system-status">
            <FiActivity aria-hidden="true" />
            <span className={`status-dot status-${backendStatus}`} />
            <span>API {backendStatus}</span>
          </div>
          <div className="version-label">FOUNDATION · PHASE 1</div>
        </div>
      </aside>

      <div className="workspace">
        <header className="topbar">
          <div className="breadcrumb">Supplier reliability <span>/</span> Overview</div>
          <div className="topbar-context">Operational workspace</div>
        </header>
        <main className="main-content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

export default AppLayout
