/**
 * Auth module - Login form validation rules
 */

import * as v from 'valibot'

export const loginSchema = v.object({
  username: v.pipe(
    v.string('用户名必须是字符串'),
    v.minLength(3, '用户名至少需要 3 个字符'),
    v.maxLength(20, '用户名不能超过 20 个字符'),
  ),
  password: v.pipe(
    v.string('密码必须是字符串'),
    v.minLength(6, '密码至少需要 6 个字符'),
  ),
  remember: v.optional(v.boolean()),
})

export type LoginSchema = v.InferOutput<typeof loginSchema>
