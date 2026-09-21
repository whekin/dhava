import { defineConfig } from 'astro/config';

export default defineConfig({
  site: 'https://nakvali.whekin.dev',
  output: 'static',
  trailingSlash: 'never',
  build: { format: 'file' },
  devToolbar: { enabled: false },
});
