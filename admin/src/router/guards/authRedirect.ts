import { RoutesAlias } from '../routesAlias'

/**
 * 已登录用户离开登录页时，只接受站内绝对路径。
 */
export function authenticatedLoginRedirect(value: unknown): string {
  const redirect = Array.isArray(value) ? value[0] : value
  if (typeof redirect !== 'string') {
    return '/'
  }

  const candidate = redirect.trim()
  const path = candidate.split(/[?#]/, 1)[0]
  if (!candidate.startsWith('/') || candidate.startsWith('//') || path === RoutesAlias.Login) {
    return '/'
  }

  return candidate
}
