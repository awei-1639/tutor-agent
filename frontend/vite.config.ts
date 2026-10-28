import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(import.meta.dirname, './src') } },
  server: {
    port: 5173,
    // 允许 cpolar 内网穿透域名访问（dev server 的 DNS-rebinding 防护默认拦截未知 Host）。
    // 前导点匹配全部子域：免费版重启后子域会变，写死某一个域名每次都要改。
    allowedHosts: ['.cpolar.top'],
    // WSL + Windows 环境下显式使用 IPv4，避免 localhost 的 IPv6 回退造成代理请求延迟。
    proxy: { '/api': { target: 'http://127.0.0.1:8180', changeOrigin: true, rewrite: p => p.replace(/^\/api/, '') } },
  },
});
