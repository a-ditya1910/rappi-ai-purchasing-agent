import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Proxying keeps the browser on one origin, so there is no cors config to add
// to the platform for what is only a development convenience.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true,
                rewrite: p => p.replace(/^\/api/, '') },
      '/agent': { target: 'http://localhost:8100', changeOrigin: true,
                  rewrite: p => p.replace(/^\/agent/, '') },
    },
  },
})
