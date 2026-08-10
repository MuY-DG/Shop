import { readdirSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { join } from 'node:path'

const collectTests = (directory) =>
  readdirSync(directory, { withFileTypes: true })
    .flatMap((entry) => {
      const path = join(directory, entry.name)
      if (entry.isDirectory()) return collectTests(path)
      return entry.isFile() && entry.name.endsWith('.test.ts') ? [path] : []
    })
    .sort()

const testFiles = collectTests('src')
if (!testFiles.length) {
  throw new Error('No admin test files were found')
}

const result = spawnSync(process.execPath, ['--import', 'tsx', '--test', ...testFiles], {
  stdio: 'inherit'
})

if (result.error) throw result.error
process.exitCode = result.status ?? 1
