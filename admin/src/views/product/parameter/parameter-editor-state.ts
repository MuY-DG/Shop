export const supportsParameterFiltering = (valueType: string): boolean =>
  valueType === 'SINGLE_SELECT' || valueType === 'MULTI_SELECT'

export const nextParameterOptionCode = (
  options: ReadonlyArray<{ optionCode?: string | null }>
): string => {
  const usedCodes = new Set(
    options
      .map((option) => option.optionCode?.trim().toUpperCase())
      .filter((code): code is string => Boolean(code))
  )
  let sequence = 1
  while (usedCodes.has(`OPTION_${sequence}`)) sequence += 1
  return `OPTION_${sequence}`
}
