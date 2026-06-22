/**
 * Checkpoint module - API client
 */
import { $adminApi } from '~/api/admin-api-client'
import type { PagerPayload } from '~/shared/types/api'
import type {
  CheckpointDTO,
  CheckpointSearchParams,
  CheckpointFilterParams,
  RepoOption,
} from '../types/checkpoint.types'

function buildSearchParams(params: CheckpointSearchParams | CheckpointFilterParams): URLSearchParams {
  const sp = new URLSearchParams()
  if (params.startTime != null) sp.set('startTime', String(params.startTime))
  if (params.endTime != null) sp.set('endTime', String(params.endTime))
  params.repoIds?.forEach((id) => sp.append('repoIds', String(id)))
  params.commitAuthorNames?.forEach((name) => sp.append('commitAuthorNames', name))
  if (params.commitMessage) sp.set('commitMessage', params.commitMessage)
  return sp
}

export const checkpointApi = {
  /** Search checkpoints (paginated). Uses 1-based page; backend one-indexed-parameters converts to 0-based. */
  async search(
    params: CheckpointSearchParams,
    pager: { page: number; size: number }
  ): Promise<PagerPayload<CheckpointDTO>> {
    const sp = buildSearchParams(params)
    sp.set('page', String(pager.page))
    sp.set('size', String(pager.size))
    return $adminApi<PagerPayload<CheckpointDTO>>(`/checkpoint/search?${sp.toString()}`, {
      method: 'GET',
    })
  },

  /** Get repos for filter dropdown */
  async getRepos(params: CheckpointFilterParams): Promise<RepoOption[]> {
    const sp = buildSearchParams(params)
    return $adminApi<RepoOption[]>(`/checkpoint/repos?${sp.toString()}`, {
      method: 'GET',
    })
  },

  /** Get commit authors for filter dropdown */
  async getCommitAuthors(params: CheckpointFilterParams): Promise<string[]> {
    const sp = buildSearchParams(params)
    return $adminApi<string[]>(`/checkpoint/commit-authors?${sp.toString()}`, {
      method: 'GET',
    })
  },

  /** Sync checkpoints for a single repo (trigger Checkpoint sync) */
  async syncRepo(repoId: number, fullScan = false): Promise<void> {
    const params = fullScan ? '?fullScan=true' : ''
    return $adminApi<void>(`/checkpoint/sync/repo/${repoId}${params}`, {
      method: 'POST',
    })
  },

  /** Sync checkpoints for all configured repos immediately */
  async syncAll(fullScan = false): Promise<void> {
    const params = fullScan ? '?fullScan=true' : ''
    return $adminApi<void>(`/checkpoint/sync/all${params}`, {
      method: 'POST',
    })
  },
}
