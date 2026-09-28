// Only this platform-owned script is executed. Generated config/package scripts are rejected.
import { build } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const root = path.resolve(process.argv[2])
const here = path.dirname(fileURLToPath(import.meta.url))
const within = (file, dir) => file === dir || file.startsWith(dir + path.sep)
const guard = {
  name: 'infi-filesystem-boundary', enforce: 'pre',
  load(id) {
    if (id.startsWith('\0')) return
    const file = path.resolve(id.split('?')[0])
    if (!within(file, root) && !within(file, path.join(here, 'node_modules'))) {
      throw new Error('Import outside generated project is forbidden')
    }
  },
}
await build({
  root, configFile: false, envFile: false, base: './', publicDir: false,
  plugins: [guard, vue()],
  resolve: { alias: { vue: path.join(here, 'node_modules/vue/dist/vue.esm-bundler.js') } },
  build: { outDir: 'dist', emptyOutDir: true, sourcemap: false, minify: false },
})
