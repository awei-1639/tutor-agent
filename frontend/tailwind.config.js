/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: { 50: '#f7f7f4', 100: '#e9e8e2', 200: '#d5d4cc', 300: '#b4b3ac', 500: '#74736d', 700: '#45443f', 900: '#181817' },
        accent: { 50: '#eef2ff', 100: '#dfe7fb', 500: '#2f4fd4', 600: '#2440b4', 700: '#203aa1' },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'sans-serif'],
        serif: ['Georgia', 'Songti SC', 'SimSun', 'serif'],
        mono: ['SF Mono', 'Consolas', 'Courier New', 'monospace'],
      },
      boxShadow: {
        soft: '0 2px 8px rgba(24,24,23,0.05), 0 1px 2px rgba(24,24,23,0.04)',
        lift: '0 18px 45px rgba(24,24,23,0.10), 0 4px 12px rgba(24,24,23,0.05)',
        // v2 三级阴影系统
        sm: '0 1px 2px rgba(26,25,23,.05)',
        md: '0 2px 6px rgba(26,25,23,.05), 0 8px 24px rgba(26,25,23,.06)',
        pop: '0 4px 12px rgba(26,25,23,.08), 0 16px 48px rgba(26,25,23,.12)',
      },
    },
  },
  plugins: [],
};
