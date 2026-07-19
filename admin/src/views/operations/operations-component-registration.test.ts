import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const readComponent = (fileName: string) =>
  readFileSync(new URL(`./components/${fileName}`, import.meta.url), 'utf8')

test('shared report page explicitly imports every local operation component used in its template', () => {
  const source = readComponent('operation-report-page.vue')

  for (const component of [
    'OperationChart',
    'OperationMetricCard',
    'OperationPeriodToolbar',
    'OperationReportPanel'
  ]) {
    assert.match(source, new RegExp(`import ${component} from './operation-`))
  }
})

test('breakdown panel explicitly imports its local panel and chart components', () => {
  const source = readComponent('operation-breakdown-panel.vue')

  assert.match(source, /import OperationChart from '\.\/operation-chart\.vue'/)
  assert.match(source, /import OperationReportPanel from '\.\/operation-report-panel\.vue'/)
})

test('operation chart applies pending options when a lazy chart becomes visible', () => {
  const source = readComponent('operation-chart.vue')

  assert.match(source, /const handleVisible = \(\) => updateChart\(props\.options\)/)
  assert.match(source, /addEventListener\('chartVisible', handleVisible\)/)
  assert.match(source, /removeEventListener\('chartVisible', handleVisible\)/)
})
