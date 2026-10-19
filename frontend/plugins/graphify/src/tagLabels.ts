const PASTE_ASSET_REFERENCE = /paste-[0-9a-f]{8,}/i
const IMAGE_PLACEHOLDER = /^(?:pasted image(?: note| attachment)?|referenced image attachment\b.*|image attachment\s*:.*|paste note\s*:.*)$/i
const HASH_ONLY = /^(?:[0-9a-f]{16,}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/i

export function isMeaningfulTag(label: string): boolean {
  const value = label.trim()
  if (!value) return false
  if (PASTE_ASSET_REFERENCE.test(value)) return false
  if (IMAGE_PLACEHOLDER.test(value)) return false
  return !HASH_ONLY.test(value)
}
