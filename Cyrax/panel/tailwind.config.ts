import type { Config } from 'tailwindcss'

const config: Config = {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        surface: '#0f1117',
        panel:   '#1a1d27',
        border:  '#2d3048',
        accent:  '#7c3aed',
        danger:  '#ef4444',
        ok:      '#22c55e'
      }
    }
  },
  plugins: []
}

export default config
