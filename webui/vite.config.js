import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 前后端分离部署：
// - 生产：Nginx 托管 dist/ 静态文件，/api、/download、/download-zip 反代到后端容器
// - 开发：Vite dev server 通过下面 proxy 直连本地后端 127.0.0.1:18423
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500,
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:18423', changeOrigin: true },
      '/download': { target: 'http://127.0.0.1:18423', changeOrigin: true },
      '/download-zip': { target: 'http://127.0.0.1:18423', changeOrigin: true },
    },
  },
})
