/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: { 50: '#f7f8fc', 100: '#edf0f7', 200: '#dce1ec', 300: '#b9c1d1', 500: '#647087', 700: '#34405a', 900: '#111a2e' },
        accent: { 50: '#f2efff', 500: '#7657f6', 600: '#6242e8', 700: '#4f31c6' },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'sans-serif'],
      },
      boxShadow: {
        soft: '0 2px 8px rgba(30,41,59,0.05), 0 1px 2px rgba(30,41,59,0.04)',
        lift: '0 18px 45px rgba(38,31,84,0.12), 0 4px 12px rgba(30,41,59,0.06)',
      },
    },
  },
  plugins: [],
};
