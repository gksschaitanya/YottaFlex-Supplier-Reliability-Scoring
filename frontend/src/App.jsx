import { Route, Routes } from 'react-router-dom'
import AppLayout from './components/layout/AppLayout.jsx'
import OverviewPage from './features/dashboard/OverviewPage.jsx'
import ExceptionsPage from './features/exceptions/ExceptionsPage.jsx'
import ScoringPage from './features/scoring/ScoringPage.jsx'
import SuppliersPage from './features/suppliers/SuppliersPage.jsx'
import './App.css'

function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<OverviewPage />} />
        <Route path="exceptions" element={<ExceptionsPage />} />
        <Route path="exceptions/:exceptionId" element={<ExceptionsPage />} />
        <Route path="suppliers" element={<SuppliersPage />} />
        <Route path="scoring" element={<ScoringPage />} />
        <Route path="*" element={<main className="not-found">Page not found</main>} />
      </Route>
    </Routes>
  )
}

export default App
