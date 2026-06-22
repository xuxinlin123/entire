/**
 * Repository module - Valibot validation schema
 */

import * as v from 'valibot'

/** Repository create/edit form validation schema */
export const repoFormSchema = v.object({
  name: v.pipe(
    v.string('名称必须是字符串'),
    v.minLength(1, '名称不能为空'),
    v.maxLength(255, '名称不能超过 255 个字符'),
  ),
  webUrl: v.pipe(
    v.string('仓库地址必须是字符串'),
    v.url('请输入有效的 URL'),
  ),
  platform: v.pipe(
    v.string('平台必须是字符串'),
    v.minLength(1, '请选择平台'),
  ),
  accessToken: v.optional(v.string()),
})
