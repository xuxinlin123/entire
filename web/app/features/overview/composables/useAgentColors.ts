/**
 * Agent color mapping - shared between ContributionChart scatter plot and CheckpointsList badges.
 * Colors must stay in sync for consistent visual association.
 */
const AGENT_COLORS: Record<string, string> = {
  'Claude Code': '#f97316',
  'OpenCode': '#a855f7',
  'Cursor': '#94a3b8',
  'Copilot': '#6ee7b7',
  'Codeium': '#fbbf24',
  '未知': '#9ca3af',
}

export function useAgentColors() {
  function getAgentColor(agent: string): string {
    const name = agent || '未知'
    return (
      AGENT_COLORS[name] ??
      `hsl(${name.split('').reduce((a, c) => a + c.charCodeAt(0), 0) % 360}, 60%, 50%)`
    )
  }

  /** Whether background is light (use dark text for contrast) */
  function isLightColor(hex: string): boolean {
    if (hex.startsWith('hsl')) {
      const m = hex.match(/hsl\((\d+),\s*(\d+)%,\s*(\d+)%\)/)
      if (m) return Number(m[3]) > 60
      return false
    }
    const r = parseInt(hex.slice(1, 3), 16) / 255
    const g = parseInt(hex.slice(3, 5), 16) / 255
    const b = parseInt(hex.slice(5, 7), 16) / 255
    const luminance = 0.299 * r + 0.587 * g + 0.114 * b
    return luminance > 0.6
  }

  /** Badge style: soft background + colored text, matches scatter plot colors */
  function getAgentBadgeStyle(agent: string): { backgroundColor: string; color: string; borderColor: string } {
    const c = getAgentColor(agent)
    if (c.startsWith('#')) {
      return {
        backgroundColor: `${c}20`,
        color: c,
        borderColor: `${c}40`,
      }
    }
    const m = c.match(/hsl\((\d+),\s*(\d+)%,\s*(\d+)%\)/)
    if (m) {
      const [, h, s, l] = m
      return {
        backgroundColor: `hsla(${h}, ${s}%, ${l}%, 0.15)`,
        color: c,
        borderColor: `hsla(${h}, ${s}%, ${l}%, 0.4)`,
      }
    }
    return { backgroundColor: 'transparent', color: c, borderColor: 'transparent' }
  }

  return { getAgentColor, isLightColor, getAgentBadgeStyle }
}
