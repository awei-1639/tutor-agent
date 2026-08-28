/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: { 50: '#f7f7f4', 100: '#e9e8e2', 200: '#d5d4cc', 300: '#b4b3ac', 500: '#74736d', 700: '#45443f', 900: '#181817' },
        accent: { 50: '#eef2ff', 500: '#3155d9', 600: '#2747c2', 700: '#203aa1' },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'sans-serif'],
      },
      boxShadow: {
        soft: '0 2px 8px rgba(24,24,23,0.05), 0 1px 2px rgba(24,24,23,0.04)',
        lift: '0 18px 45px rgba(24,24,23,0.10), 0 4px 12px rgba(24,24,23,0.05)',
      },
    },
  },
  plugins: [],
};
