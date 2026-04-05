/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,jsx,ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        /* ── Brand purple/black palette ── */
        bg:       "#07070E",
        surface:  "#0D0D1A",
        card:     "#111122",
        elevated: "#16162A",
        hover:    "#1C1C35",

        /* Purple spectrum */
        purple: {
          50:  "#F5F3FF",
          100: "#EDE9FE",
          200: "#DDD6FE",
          300: "#C4B5FD",
          400: "#A78BFA",
          500: "#8B5CF6",
          600: "#7C3AED",
          700: "#6D28D9",
          800: "#5B21B6",
          900: "#4C1D95",
        },

        /* Semantic */
        accent:  "#8B5CF6",
        "accent-bright": "#A78BFA",
        "accent-glow":   "rgba(139,92,246,0.18)",
        "accent-dim":    "rgba(139,92,246,0.10)",

        ink:    "#F0ECFF",
        sub:    "#8B7FA8",
        muted:  "#4A4464",
        border: "rgba(139,92,246,0.12)",
        "border-hi": "rgba(139,92,246,0.28)",

        /* Status */
        success:  "#34D399",
        warning:  "#FCD34D",
        danger:   "#F87171",
        info:     "#60A5FA",
      },

      fontFamily: {
        sans:  ["'Plus Jakarta Sans'", "system-ui", "sans-serif"],
        mono:  ["'JetBrains Mono'", "monospace"],
        display: ["'Syne'", "'Plus Jakarta Sans'", "sans-serif"],
      },

      borderRadius: {
        "xl":  "14px",
        "2xl": "18px",
        "3xl": "22px",
        "4xl": "28px",
      },

      boxShadow: {
        "purple-sm":  "0 2px 8px rgba(139,92,246,.2)",
        "purple-md":  "0 4px 20px rgba(139,92,246,.3)",
        "purple-lg":  "0 8px 40px rgba(139,92,246,.35)",
        "purple-glow":"0 0 30px rgba(139,92,246,.4), 0 0 60px rgba(139,92,246,.2)",
        "card":       "0 2px 16px rgba(0,0,0,.4)",
        "card-hover": "0 8px 32px rgba(0,0,0,.5), 0 0 0 1px rgba(139,92,246,.1)",
      },

      animation: {
        "fade-up":    "fadeUp .45s cubic-bezier(.25,.46,.45,.94) both",
        "fade-in":    "fadeIn .3s ease both",
        "slide-in":   "slideIn .35s ease both",
        "pulse-slow": "pulseSlow 2s ease-in-out infinite",
        "glow":       "glow 3s ease-in-out infinite",
        "spin-slow":  "spin 3s linear infinite",
        "ticker":     "ticker .3s ease both",
        "shimmer":    "shimmer 1.5s ease infinite",
        "drift":      "drift 18s ease-in-out infinite",
        "drift2":     "drift2 22s ease-in-out infinite",
        "scan":       "scan 4s linear infinite",
      },

      keyframes: {
        fadeUp:    { "0%":{ opacity:0, transform:"translateY(18px)" }, "100%":{ opacity:1, transform:"translateY(0)" } },
        fadeIn:    { "0%":{ opacity:0 }, "100%":{ opacity:1 } },
        slideIn:   { "0%":{ transform:"translateX(-16px)", opacity:0 }, "100%":{ transform:"translateX(0)", opacity:1 } },
        pulseSlow: { "0%,100%":{ opacity:1, transform:"scale(1)" }, "50%":{ opacity:.45, transform:"scale(.88)" } },
        glow:      { "0%,100%":{ boxShadow:"0 0 12px rgba(139,92,246,.25)" }, "50%":{ boxShadow:"0 0 28px rgba(139,92,246,.6)" } },
        ticker:    { "0%":{ opacity:0, transform:"translateY(8px)" }, "100%":{ opacity:1, transform:"translateY(0)" } },
        shimmer:   { "0%":{ backgroundPosition:"200% 0" }, "100%":{ backgroundPosition:"-200% 0" } },
        drift:     { "0%,100%":{ transform:"translate(0,0) scale(1)" }, "50%":{ transform:"translate(40px,-30px) scale(1.1)" } },
        drift2:    { "0%,100%":{ transform:"translate(0,0)" }, "50%":{ transform:"translate(-50px,40px)" } },
        scan:      { "0%":{ top:"-5%" }, "100%":{ top:"105%" } },
      },

      backgroundImage: {
        "purple-gradient":   "linear-gradient(135deg, #6D28D9, #8B5CF6)",
        "purple-gradient-t": "linear-gradient(135deg, #8B5CF6, #A78BFA)",
        "card-shine":        "linear-gradient(135deg, rgba(139,92,246,.08), transparent)",
        "grid-pattern":      "linear-gradient(rgba(139,92,246,.04) 1px,transparent 1px), linear-gradient(90deg,rgba(139,92,246,.04) 1px,transparent 1px)",
      },

      backgroundSize: {
        "grid": "48px 48px",
      },
    },
  },
  plugins: [],
};
