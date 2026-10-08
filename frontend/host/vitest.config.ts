import { defineConfig } from 'vitest/config'
import { fileURLToPath, URL } from 'node:url'

/**
 * Unit tests for framework-independent frontend logic (markdown rendering, utils).
 * Component/DOM tests would need jsdom; the canvas interactions are covered by the
 * HTTP-level end-to-end scripts plus manual checks (see docs/GAPS.md §5).
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
  },
})
