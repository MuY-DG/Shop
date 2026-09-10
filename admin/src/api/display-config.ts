import request from '@/utils/http'

export interface DisplayConfig {
  displayName: string
  version: number
  updatedAt: string
}

export function fetchDisplayConfig() {
  return request.get<DisplayConfig>({ url: '/admin/display-config' })
}

export function updateDisplayConfig(data: Pick<DisplayConfig, 'displayName' | 'version'>) {
  return request.put<DisplayConfig>({
    url: '/admin/display-config',
    data,
    showSuccessMessage: false
  })
}
