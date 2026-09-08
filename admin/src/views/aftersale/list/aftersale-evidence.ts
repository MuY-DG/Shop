export function isPreviewableImage(file: Api.AfterSale.EvidenceFile): boolean {
  return (
    file.status === 'ACTIVE' &&
    file.mediaKind === 'IMAGE' &&
    file.contentType.toLowerCase().startsWith('image/')
  )
}

export function isPreviewableVideo(file: Api.AfterSale.EvidenceFile): boolean {
  return (
    file.status === 'ACTIVE' &&
    file.mediaKind === 'VIDEO' &&
    file.contentType.toLowerCase().startsWith('video/')
  )
}

export function evidenceImageUrls(
  files: Api.AfterSale.EvidenceFile[],
  urls: Record<number, string>
): string[] {
  return files
    .filter(isPreviewableImage)
    .map((file) => urls[file.fileId])
    .filter(Boolean)
}
