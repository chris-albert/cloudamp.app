import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "path";

export default defineConfig({
  base: "/cloudamp.app/",
  plugins: [react(), tailwindcss()],
  server: {
    port: parseInt(process.env.WEB_PORT || "5050", 10),
    strictPort: true,
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
});
