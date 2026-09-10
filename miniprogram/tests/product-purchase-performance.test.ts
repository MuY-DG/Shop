import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';
import ts from 'typescript';
import * as catalog from '../miniprogram/features/product-catalog';
import type { ProductSku, ProductDetail } from '../miniprogram/types/product';

function runtime(kind: 'page' | 'gallery' = 'page') {
  let instance: any;
  const loads: string[] = [], previews: any[] = [], patches: any[] = [];
  const renders: Array<() => void> = [];
  const pending: Array<() => void> = [];
  let visible: (event: any) => void = () => {};
  const path = kind === 'page' ? 'pages/product/detail/detail.ts' : 'components/product-gallery/product-gallery.ts';
  const code = ts.transpileModule(readFileSync(resolve(process.cwd(), 'miniprogram', path), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText;
  const register = (definition: any) => {
    instance = kind === 'page' ? definition : { ...definition, ...definition.methods };
    instance.setData = (patch: any, callback?: () => void) => {
      patches.push(patch);
      for (const [path, value] of Object.entries(patch)) {
        const keys = path.replace(/\[(\d+)\]/g, '.$1').split('.');
        let target = instance.data;
        keys.slice(0, -1).forEach(key => { target = target[key]; });
        target[keys[keys.length - 1]] = value;
      }
      if (callback) renders.push(callback);
    };
    instance.createIntersectionObserver = () => ({
      relativeTo() { return this; },
      observe(_selector: string, callback: (event: any) => void) { visible = callback; return this; },
      disconnect() { visible = () => {}; }
    });
  };
  runInNewContext(code, {
    exports: {}, Page: register, Component: register, setTimeout, clearTimeout,
    wx: { nextTick: (callback: () => void) => renders.push(callback), previewImage: (args: any) => previews.push(args) },
    require: (name: string) => {
      if (name.endsWith('features/product-catalog')) return catalog;
      if (name.endsWith('features/product-review')) return { buildProductReviewSummaryView: () => ({}) };
      if (name.endsWith('utils/product-image-cache')) return {
        getCachedProductImage: () => null, invalidateProductImage: () => {},
        loadProductImage: (url: string) => {
          loads.push(url);
          return new Promise<string>(resolve => pending.push(() => resolve(`wxfile://${loads.indexOf(url)}`)));
        }
      };
      return {};
    }
  });
  const skus: ProductSku[] = Array.from({ length: 25 }, (_, i) => ({
    id: i + 1, skuCode: `SKU${i}`, specJson: JSON.stringify({ 口味: `口味${i}` }), specText: `口味${i}`,
    priceCent: 100, image: `https://images.test/${i}.webp`, maxPurchaseQuantity: 99,
    status: 'ENABLED', saleState: 'AVAILABLE', wholesaleTiers: []
  }));
  const variants = Object.fromEntries(skus.map(s => [s.image!, {
    thumbnailUrl: s.image + '.thumb.webp', displayUrl: s.image + '.display.webp'
  }]));
  if (kind === 'page') {
    instance.requireLogin = () => true;
    instance.data.detail = { skus, specType: 'MULTI', mainImage: skus[0].image, imageVariants: variants } as ProductDetail;
    Object.assign(instance.data, catalog.resolvePurchaseSelection(skus[0], 1));
    instance.data.specificationGroups = catalog.buildVisibleSkuSpecificationGroups('MULTI', skus, 1);
    instance.data.purchaseImageUrl = skus[0].image;
    instance.data.purchaseImageSourceUrl = variants[skus[0].image!].thumbnailUrl;
  }
  return { instance, skus, loads, previews, patches,
    paint: () => { while (renders.length) renders.shift()!(); },
    visible: (group = 0, option = 0) => visible({ intersectionRatio: 1, dataset: { groupIndex: group, optionIndex: option } }),
    finish: async () => { pending.splice(0).forEach(f => f()); await Promise.resolve(); }
  };
}

test('25 个 SKU 点击购买先展开弹层，只在渲染后加载头图及可见缩略图', async () => {
  const r = runtime();
  r.instance.onOpenPurchase({ currentTarget: { dataset: { mode: 'BUY' } } });
  assert.equal(r.instance.data.purchaseSheetOpen, true);
  assert.deepEqual(r.loads, []);
  r.paint();
  assert.deepEqual(r.loads, ['https://images.test/0.webp.thumb.webp']);
  r.visible(0, 1);
  assert.equal(r.loads[r.loads.length - 1], 'https://images.test/1.webp.thumb.webp');
  assert.equal(r.loads.length, 2);
  await r.finish();
  assert.ok(r.instance.data.specificationGroups[0].options[1].displayUrl.startsWith('wxfile://'));
});

test('只修改购买数量时保持规格列表和图片观察器不变', () => {
  const r = runtime();
  r.instance.onOpenPurchase({ currentTarget: { dataset: {} } });
  r.paint();
  const groups = r.instance.data.specificationGroups;
  const loads = r.loads.length;
  r.instance.applySelection(r.skus[0], 2);
  assert.equal(r.instance.data.quantity, 2);
  assert.equal(r.instance.data.specificationGroups, groups);
  assert.equal(r.loads.length, loads);
  assert.equal('specificationGroups' in r.patches[r.patches.length - 1], false);
});

test('SKU 大图模式使用展示图，放大预览直接打开高清图，卸载后丢弃迟到图片', async () => {
  const r = runtime();
  r.instance.onOpenPurchase({ currentTarget: { dataset: {} } });
  r.paint();
  r.instance.onSpecificationImageModeToggle();
  r.paint();
  r.visible(0, 3);
  assert.equal(r.loads[r.loads.length - 1], 'https://images.test/3.webp.display.webp');
  const before = r.loads.length;
  r.instance.onPreviewSpecificationImage({ currentTarget: { dataset: { imageUrl: r.skus[3].image } } });
  assert.equal(r.loads.length, before);
  assert.equal(r.previews[0].current, r.skus[3].image);
  r.instance.onUnload();
  const patches = r.patches.length;
  await r.finish();
  assert.equal(r.patches.length, patches);
});

test('详情轮播首屏只加载当前和下一张，预览不等待整组图片下载', async () => {
  const r = runtime('gallery');
  const images = Array.from({ length: 9 }, (_, i) => ({ key: String(i), url: `https://images.test/${i}.display.webp`,
    previewUrl: `https://images.test/${i}.webp`, hasImage: true }));
  r.instance.properties.images.observer.call(r.instance, images);
  assert.equal(r.loads.length, 0);
  r.paint();
  assert.equal(r.loads.length, 2);
  r.instance.onChange({ detail: { current: 2 } });
  assert.equal(r.loads.length, 4);
  r.instance.onImagePreview({ currentTarget: { dataset: { index: 2 } } });
  assert.equal(r.previews[0].current, images[2].previewUrl);
  assert.equal(r.loads.length, 4);
  r.instance.lifetimes.detached.call(r.instance);
  const patches = r.patches.length;
  await r.finish();
  assert.equal(r.patches.length, patches);
});

test('尚未加载的空图片错误不会触发所有高清图下载', () => {
  const r = runtime();
  r.instance.onOpenPurchase({ currentTarget: { dataset: {} } });
  const before = r.patches.length;
  r.instance.onPurchaseImageError({ currentTarget: { dataset: { groupIndex: 0, optionIndex: 20 } } });
  assert.equal(r.patches.length, before);
  assert.equal(r.loads.length, 0);
});

test('轮播使用展示图但保留高清预览地址，旧接口未返回派生图时仍可展示', () => {
  const r = runtime();
  const detail = { ...r.instance.data.detail, images: [{ url: r.skus[0].image, sortOrder: 0 }] };
  const [image] = catalog.buildGalleryImages(detail);
  assert.equal(image.url, r.skus[0].image + '.display.webp');
  assert.equal(image.previewUrl, r.skus[0].image);
  const [legacy] = catalog.buildGalleryImages({ ...detail, imageVariants: undefined });
  assert.equal(legacy.url, r.skus[0].image);
});
