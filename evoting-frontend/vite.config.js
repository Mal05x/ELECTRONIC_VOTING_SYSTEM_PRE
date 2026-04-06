import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";
import fs from "fs";
import https from "https";

export default defineConfig(({ command }) => {
  // ==========================================
  // 1. BASE CONFIG (Applies to everywhere)
  // ==========================================
  const baseConfig = {
    plugins: [react()],
    resolve: {
      alias: { "@": path.resolve(__dirname, "./src") },
    },
  };

  // ==========================================
  // 2. VERCEL DEPLOYMENT (Clean Build)
  // ==========================================
  // If Vercel is building the app, completely ignore the C:/ drive!
  if (command === "build" || process.env.VERCEL) {
    return {
      ...baseConfig,
      build: {
        outDir: "dist",
        sourcemap: false,
      },
    };
  }

  // ==========================================
  // 3. LOCAL DEVELOPMENT (Your Laptop Only)
  // ==========================================
  let localServerConfig = { port: 3000 };

  try {
    const localKey = fs.readFileSync("C:/evoting_certs/frontend_localhost.key");
    const localCert = fs.readFileSync("C:/evoting_certs/frontend_localhost.crt");

    const mtlsAgent = new https.Agent({
      key: localKey,
      cert: localCert,
      rejectUnauthorized: false
    });

    localServerConfig = {
      port: 3000,
      https: { key: localKey, cert: localCert },
      proxy: {
        "/api": {
          target: "https://localhost:8443",
          changeOrigin: true,
          secure: false,
          key: localKey,
          cert: localCert
        },
        "/ws": {
          target: "https://localhost:8443",
          changeOrigin: true,
          ws: true,
          secure: false,
          agent: mtlsAgent,
          key: localKey,
          cert: localCert
        }
      }
    };
  } catch (err) {
    console.warn("⚠️ Local certificates not found. Starting without mTLS.");
  }

  return {
    ...baseConfig,
    server: localServerConfig
  };
});