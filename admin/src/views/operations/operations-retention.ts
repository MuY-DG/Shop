import { formatUnitValue } from './operations-state'

export const retentionCohortLabel = (cohort: Api.Operations.RetentionCohortItem): string =>
  cohort.cohortStartDate === cohort.cohortEndDate
    ? cohort.cohortStartDate
    : `${cohort.cohortStartDate} 至 ${cohort.cohortEndDate}`

export function retentionWindowText(
  cohort: Api.Operations.RetentionCohortItem,
  dayOffset: 1 | 7 | 30
): string {
  const window = cohort.windows.find((item) => item.dayOffset === dayOffset)
  if (
    !window ||
    typeof window.retainedUserCount !== 'number' ||
    !Number.isFinite(window.retainedUserCount) ||
    typeof window.retentionRateBasisPoints !== 'number' ||
    !Number.isFinite(window.retentionRateBasisPoints) ||
    !Number.isFinite(window.eligibleUserCount)
  ) {
    return '未成熟 / 未采集'
  }
  return `${formatUnitValue(window.retentionRateBasisPoints, 'BASIS_POINT')}（${window.retainedUserCount}/${window.eligibleUserCount}）`
}
