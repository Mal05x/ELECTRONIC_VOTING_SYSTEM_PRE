import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";
import fs from 'fs';
import https from 'https'; // <-- 1. Add this import

// 2. Create a custom agent holding your certificates
const mtlsAgent = new https.Agent({
  key: fs.readFileSync('C:/evoting_certs/frontend_localhost.key'),
  cert: fs.readFileSync('C:/evoting_certs/frontend_localhost.crt'),
  rejectUnauthorized: false // Bypasses self-signed cert issues for this agent
});

export default defineConfig({
  // FIX: Closed the plugins array properly!
  plugins: [react()],

  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },

  // Proxy API calls to Spring Boot backend during local dev.
  // In production (Vercel), VITE_API_URL env var is used directly.
  server: {
    port: 3000,
    https: {
      // Point Node.js to your actual INEC certificate files!
     key: fs.readFileSync('C:/evoting_certs/frontend_localhost.key'),
     cert: fs.readFileSync('C:/evoting_certs/frontend_localhost.crt'),
    },
    proxy: {
      "/api": {
        target: process.env.VITE_API_URL || "https://localhost:8443",
        changeOrigin: true,
        secure: false, // allow self-signed certs in dev
        // 2. This secures Vite <--> Spring Boot (mTLS!)
                key: fs.readFileSync('C:/evoting_certs/frontend_localhost.key'),
                cert: fs.readFileSync('C:/evoting_certs/frontend_localhost.crt'),
      },
      "/ws": {
        target: process.env.VITE_API_URL || "https://localhost:8443",
        changeOrigin: true,
        ws: true,
        secure: false,
         // 3. Remove the old key/cert lines here and use the agent instead!
        agent: mtlsAgent,
        // 2. The raw Key/Cert secures the actual WebSocket Upgrade connection!
                key: fs.readFileSync('C:/evoting_certs/frontend_localhost.key'),
                cert: fs.readFileSync('C:/evoting_certs/frontend_localhost.crt')
      },
    },
  },

  build: {
    outDir: "dist",
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ["react", "react-dom", "react-router-dom"],
          charts: ["recharts"],
        },
      },
    },
  },
});