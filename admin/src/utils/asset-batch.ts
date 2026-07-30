export const ASSET_UPLOAD_CONCURRENCY = 2

export async function settleWithConcurrency<T, R>(
  items: readonly T[],
  concurrency: number,
  worker: (item: T, index: number) => Promise<R>
): Promise<PromiseSettledResult<R>[]> {
  if (!Number.isInteger(concurrency) || concurrency < 1) {
    throw new RangeError('concurrency must be a positive integer')
  }

  const results = new Array<PromiseSettledResult<R>>(items.length)
  let nextIndex = 0

  const runWorker = async () => {
    while (nextIndex < items.length) {
      const index = nextIndex++
      try {
        results[index] = { status: 'fulfilled', value: await worker(items[index], index) }
      } catch (reason) {
        results[index] = { status: 'rejected', reason }
      }
    }
  }

  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, () => runWorker()))
  return results
}

const assetValueKey = (asset: Api.Common.AssetValue) =>
  asset.fileId !== null ? `file:${asset.fileId}` : asset.url ? `url:${asset.url}` : ''

export function appendUniqueAssetValues(
  existing: readonly Api.Common.AssetValue[],
  incoming: readonly Api.Common.AssetValue[],
  maxCount: number
): Api.Common.AssetValue[] {
  const result: Api.Common.AssetValue[] = []
  const seen = new Set<string>()

  for (const asset of [...existing, ...incoming]) {
    const key = assetValueKey(asset)
    if (!key || seen.has(key)) continue
    seen.add(key)
    result.push({ fileId: asset.fileId, url: asset.url })
    if (result.length >= maxCount) break
  }

  return result
}
