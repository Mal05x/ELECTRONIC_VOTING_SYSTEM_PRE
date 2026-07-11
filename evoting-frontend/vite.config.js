import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    // If you are accessing Vite from another device, you need this to expose the server
    host: '0.0.0.0', 
    proxy: {
      "/api": {
        // Change this to the exact IP of your Spring Boot server
        target: "https://mfa-evoting-backend.onrender.com", 
        changeOrigin: true,
        secure: false,
      },
    },
  },
});
