/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: { 50: '#f7f7f8', 100: '#eeeef0', 200: '#d8d8de', 300: '#b3b3bd', 500: '#6c6c79', 700: '#3a3a44', 900: '#1a1a22' },
        accent: { 50: '#ecfdf5', 500: '#10b981', 600: '#059669', 700: '#047857' },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'sans-serif'],
      },
      boxShadow: {
        soft: '0 1px 2px rgba(15,15,20,0.04), 0 1px 3px rgba(15,15,20,0.06)',
        lift: '0 4px 12px rgba(15,15,20,0.08), 0 2px 4px rgba(15,15,20,0.06)',
      },
    },
  },
  plugins: [],
};