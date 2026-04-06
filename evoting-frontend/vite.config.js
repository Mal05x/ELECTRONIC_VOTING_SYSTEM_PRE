import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";
import fs from "fs";
import https from "https";

export default defineConfig(({ command }) => {
  // If Vercel is building the app, completely ignore the C:/ drive!
  if (command === "build" || process.env.VERCEL) {
    return {
      plugins: [react()],
      resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
      build: { outDir: "dist", sourcemap: false },
    };
  }

  // Local Development Only
  let localServerConfig = { port: 3000 };

  try {
    const localKey = fs.readFileSync("C:/evoting_certs/frontend_localhost.key");
    const localCert = fs.readFileSync("C:/evoting_certs/frontend_localhost.crt");

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
        }
      }
    };
  } catch (err) {
    console.warn("Local certificates not found.");
  }

  return {
    plugins: [react()],
    resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
    server: localServerConfig
  };
});