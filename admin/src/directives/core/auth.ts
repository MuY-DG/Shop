/**
 * v-auth 权限指令
 *
 * 适用于后端权限控制模式，基于权限标识控制 DOM 元素的显示和隐藏。
 * 权限变化时会更新元素可见性，避免路由权限尚未就绪时永久移除元素。
 *
 * ## 主要功能
 *
 * - 权限验证 - 根据路由 meta 中的权限列表验证用户权限
 * - DOM 控制 - 无权限时隐藏元素，权限恢复后重新显示
 * - 响应式更新 - 权限变化时自动更新元素状态
 *
 * ## 使用示例
 *
 * ```vue
 * <!-- 只有拥有 'add' 权限的用户才能看到新增按钮 -->
 * <el-button v-auth="'add'">新增</el-button>
 *
 * <!-- 只有拥有 'edit' 权限的用户才能看到编辑按钮 -->
 * <el-button v-auth="'edit'">编辑</el-button>
 *
 * <!-- 只有拥有 'delete' 权限的用户才能看到删除按钮 -->
 * <el-button v-auth="'delete'">删除</el-button>
 * ```
 *
 * ## 注意事项
 *
 * - 权限列表从当前路由的 meta.authList 中获取
 *
 * @module directives/auth
 * @author Art Design Pro Team
 */

import { router } from '@/router'
import { ref, watchEffect, type App, type Directive, type Ref } from 'vue'

export type AuthDirective = Directive<HTMLElement, string>

interface AuthDirectiveState {
  originalDisplay: string
  permission: Ref<string>
  stop: () => void
}

const directiveStates = new WeakMap<HTMLElement, AuthDirectiveState>()

function hasAuthPermission(permission: string): boolean {
  // 获取当前路由的权限列表
  const authList = (router.currentRoute.value.meta.authList as Array<{ authMark: string }>) || []
  return authList.some((item) => item.authMark === permission)
}

const authDirective: AuthDirective = {
  mounted(el, binding) {
    const permission = ref(binding.value)
    const originalDisplay = el.style.display
    const stop = watchEffect(() => {
      el.style.display = hasAuthPermission(permission.value) ? originalDisplay : 'none'
    })
    directiveStates.set(el, { originalDisplay, permission, stop })
  },
  updated(el, binding) {
    const state = directiveStates.get(el)
    if (state) state.permission.value = binding.value
  },
  beforeUnmount(el) {
    const state = directiveStates.get(el)
    state?.stop()
    directiveStates.delete(el)
  }
}

export function setupAuthDirective(app: App): void {
  app.directive('auth', authDirective)
}
