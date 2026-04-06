import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";
import fs from 'fs';
import https from 'https';

// 1. Define where your local certs live
const keyPath = 'C:/evoting_certs/frontend_localhost.key';
const certPath = 'C:/evoting_certs/frontend_localhost.crt';

// 2. Safely check if they actually exist on this computer (True locally, False on Vercel)
const useHttps = fs.existsSync(keyPath) && fs.existsSync(certPath);

// 3. Only read the files if they exist
const localKey = useHttps ? fs.readFileSync(keyPath) : null;
const localCert = useHttps ? fs.readFileSync(certPath) : null;

// 4. Only create the mTLS agent if we are running locally
const mtlsAgent = useHttps ? new https.Agent({
  key: localKey,
  cert: localCert,
  rejectUnauthorized: false
}) : undefined;

export default defineConfig({
  plugins: [react()],

  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },

  server: {
    port: 3000,
    // Only turn on HTTPS locally
    ...(useHttps ? {
      https: {
        key: localKey,
        cert: localCert,
      }
    } : {}),
    proxy: {
      "/api": {
        target: process.env.VITE_API_URL || "https://localhost:8443",
        changeOrigin: true,
        secure: false,
        // Only inject mTLS keys into the proxy if running locally
        ...(useHttps ? { key: localKey, cert: localCert } : {})
      },
      "/ws": {
        target: process.env.VITE_API_URL || "https://localhost:8443",
        changeOrigin: true,
        ws: true,
        secure: false,
        // Only inject mTLS agent and keys if running locally
        ...(useHttps ? { agent: mtlsAgent, key: localKey, cert: localCert } : {})
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