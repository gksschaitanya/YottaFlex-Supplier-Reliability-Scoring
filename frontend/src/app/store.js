import { configureStore } from '@reduxjs/toolkit'
import systemReducer from '../features/system/systemSlice.js'

export const store = configureStore({
  reducer: {
    system: systemReducer,
  },
})
