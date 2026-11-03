import { createAsyncThunk, createSlice } from '@reduxjs/toolkit'
import { apiClient } from '../../api/client.js'

export const loadBackendHealth = createAsyncThunk('system/loadBackendHealth', async () => {
  const response = await apiClient.get('/actuator/health')
  return response.data.status
})

const systemSlice = createSlice({
  name: 'system',
  initialState: {
    backendStatus: 'unknown',
    healthError: null,
  },
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(loadBackendHealth.pending, (state) => {
        state.backendStatus = 'checking'
        state.healthError = null
      })
      .addCase(loadBackendHealth.fulfilled, (state, action) => {
        state.backendStatus = action.payload.toLowerCase()
        state.healthError = null
      })
      .addCase(loadBackendHealth.rejected, (state, action) => {
        state.backendStatus = 'unavailable'
        state.healthError = action.error.message
      })
  },
})

export default systemSlice.reducer
