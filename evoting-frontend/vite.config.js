import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";
import fs from 'fs';
import https from 'https';

export default defineConfig(({ command }) => {
  // ====================================================================
  // PRODUCTION (VERCEL): Clean config, no SSL certificates required
  // ====================================================================
  if (command === 'build') {
    return {
      plugins: [react()],
      resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
      build: {
        outDir: "dist",
        sourcemap: false,
        rollupOptions: {
          output: {
            manualChunks: { vendor: ["react", "react-dom", "react-router-dom"], charts: ["recharts"] },
          },
        },
      },
    };
  }

  // ====================================================================
  // LOCAL DEV: Load mTLS certificates wrapped in a safety Try/Catch
  // ====================================================================
  let localKey = null;
  let localCert = null;
  let mtlsAgent = undefined;

  try {
    localKey = fs.readFileSync('C:/evoting_certs/frontend_localhost.key');
    localCert = fs.readFileSync('C:/evoting_certs/frontend_localhost.crt');
    mtlsAgent = new https.Agent({
      key: localKey,
      cert: localCert,
      rejectUnauthorized: false
    });
  } catch (error) {
    console.warn("⚠️ Local certificates not found. Running without mTLS.");
  }

  return {
    plugins: [react()],
    resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
    server: {
      port: 3000,
      // Only enable HTTPS locally if the certs were successfully loaded
      ...(localKey && localCert ? { https: { key: localKey, cert: localCert } } : {}),
      proxy: {
        "/api": {
          target: process.env.VITE_API_URL || "https://localhost:8443",
          changeOrigin: true,
          secure: false,
          ...(localKey ? { key: localKey, cert: localCert } : {})
        },
        "/ws": {
          target: process.env.VITE_API_URL || "https://localhost:8443",
          changeOrigin: true,
          ws: true,
          secure: false,
          ...(mtlsAgent ? { agent: mtlsAgent, key: localKey, cert: localCert } : {})
        },
      },
    },
  };
});